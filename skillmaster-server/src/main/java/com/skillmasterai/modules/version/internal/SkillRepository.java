package com.skillmasterai.modules.version.internal;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.version.Caller;
import com.skillmasterai.modules.version.GrantRole;
import com.skillmasterai.modules.version.SkillMetadata;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads and writes {@code skill} — one of the four tables M7 owns. */
public final class SkillRepository {

    private final JdbcClient jdbc;

    public SkillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the skill if the name is new, and returns its id either way. Never changes an
     * existing skill.
     *
     * <p><strong>It returns the existing row without touching it, and that is the point.</strong>
     * The metadata a submission carries belongs to the version it created; the skill row's copy is
     * written only when a version is published (ADR 0031). Doing otherwise would make a submission
     * change the title and description that search shows on the consumption plane while the content
     * behind them stayed the old version — which is the thing the split exists to prevent.
     *
     * <p>The no-op {@code DO UPDATE} is not decoration. It is what makes this statement take the
     * {@code skill} row's lock even when nothing needs updating, and that lock is what serialises
     * number allocation across concurrent submissions — see
     * {@link VersionRepository#insertIfAbsent}, which depends on it and says so.
     *
     * <p>Two deliberate omissions, both kept from the statement this replaces:
     * <ul>
     *   <li><strong>{@code visibility} is never touched.</strong> §4.3 gives metadata its own
     *       endpoint; a submission that reset visibility would be a way to make a private skill
     *       public by accident.</li>
     *   <li><strong>{@code deleted_at} is not cleared</strong>, enforced by the {@code WHERE}. A
     *       soft-deleted skill therefore returns no row here, which is what lets the caller tell
     *       "deleted" from "created" and refuse instead of resurrecting (see
     *       {@link com.skillmasterai.modules.version.SkillDeletedException}).</li>
     * </ul>
     *
     * @return the skill's id, or empty when the name belongs to a soft-deleted skill
     */
    public Optional<String> findOrCreate(String namespaceId, SkillMetadata metadata,
            String createdBy, String at) {
        return jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, created_by, created_at, updated_at)
                VALUES (:id, :namespaceId, :name, :title, :description, :frontmatter,
                        'private', :createdBy, :at, :at)
                ON CONFLICT (namespace_id, name) DO UPDATE
                   SET name = skill.name
                 WHERE skill.deleted_at IS NULL
                RETURNING id
                """)
                .param("id", Ulid.generate())
                .param("namespaceId", namespaceId)
                .param("name", metadata.name())
                .param("title", metadata.title())
                .param("description", metadata.description())
                .param("frontmatter", metadata.frontmatterJson())
                .param("createdBy", createdBy)
                .param("at", at)
                .query(String.class)
                .optional();
    }

    /**
     * Makes a version the one consumers get, and makes the skill row describe it.
     *
     * <p>Two facts in one statement because they are one fact: {@code skill.title/description/
     * frontmatter} are a projection of whichever version is current (ADR 0031), so they move when
     * the pointer moves and at no other time. The version's own copy is read from
     * {@code skill_version} rather than passed in, since the caller would only have got it from
     * there. Both tables are M7's, so §2.5 rule ① is not in question.
     *
     * <p><strong>The pointer may move backwards.</strong> Publishing a version that is already
     * published is rollback — §7 once put it in P2, but the gateway forces it to exist here:
     * reverted source re-submits to the same digest (ADR 0005 makes that a no-op), so the served
     * gateway can only be brought back in line by pointing at the old version again.
     *
     * <p>The caller has already checked that the version is not discarded and is not already
     * current; the guard against a concurrent discard lives on
     * {@link VersionRepository#markPublished}.
     *
     * <p><strong>{@code IS DISTINCT FROM} is what makes the return value mean something.</strong>
     * Without it an update that sets the value the row already holds still counts as one row, so a
     * request that lost a race to publish the same version would report that it moved the pointer
     * and write an audit row for a change it did not make. With it, zero rows means precisely "the
     * pointer already named this version", and nothing is written — not the timestamp, not the
     * projected metadata, and not the caller's trail.
     *
     * @return whether the pointer actually moved
     */
    public boolean moveCurrentVersion(String skillId, String versionId, String at) {
        return jdbc.sql("""
                UPDATE skill s
                   SET current_version_id = v.id, updated_at = :at,
                       title = v.title, description = v.description, frontmatter = v.frontmatter
                  FROM skill_version v
                 WHERE s.id = :skillId AND v.id = :versionId
                   AND s.current_version_id IS DISTINCT FROM v.id
                """)
                .param("skillId", skillId)
                .param("versionId", versionId)
                .param("at", at)
                .update() > 0;
    }

    /**
     * The skill with this name in this namespace, together with what this caller may do to it.
     *
     * <p><strong>"Live" here means only "not soft-deleted", and since ADR 0031 that is a weaker
     * statement than it used to be.</strong> A skill can now exist with nothing published from it,
     * so this row may have no current version at all; whether anything is readable is a question
     * about its versions, and it is asked separately and per plane. What this method answers is
     * just "does a skill of this name exist here, and on what standing".
     *
     * <p>{@code UNIQUE(namespace_id, name)} makes this at most one row. Every read resolves its skill
     * this way, because the address is a name rather than an id (§4.1) — the gateway included, since
     * its id is not known until it has been published once.
     *
     * <p><strong>The access predicate is the whole authorization check, and that is the point of
     * putting it here.</strong> §4.2 requires an unreadable private skill to be a 404 rather than a
     * 403, because a 403 confirms it exists. Filtering in the same statement that finds the row makes
     * "not yours" and "not there" the same empty result <em>structurally</em>, rather than two
     * branches a later edit could drift apart — nothing is disclosed by the absence, and nothing
     * here is rendered as a 403.
     *
     * <p><strong>It is a disjunction, and it used to be a single comparison.</strong> Before sharing,
     * the predicate was {@code namespace_id = :namespaceId}, where the namespace was the caller's
     * own — so a skill in somebody else's namespace was not expressible rather than merely refused
     * (§3.4). Now the caller may also act on a skill granted to them, and the addressed namespace is
     * no longer necessarily theirs.
     *
     * <p><strong>One statement for three answers, and the third is why.</strong> Reading the row,
     * reading whether the caller owns its namespace, and reading which role they were granted all
     * come from the same join. Splitting them into a "may read" query and a "may write" query would
     * make each write path ask twice — and the second question is exactly the one a later edit
     * forgets, which after ADR 0034 means a viewer who can delete. The grant is a {@code LEFT JOIN}
     * rather than the correlated {@code EXISTS} it could be, because the role is wanted, not just its
     * presence.
     *
     * <p>Both tables named here are M7's, so rule 1 is untouched and the lookup stays a comparison
     * of values this layer already holds rather than a join across a module boundary.
     *
     * @return empty when there is no such live skill, <em>or</em> when there is one this caller may
     *         not see — one answer for both, deliberately; see the class note on
     *         {@code SkillVersionService}'s 404s
     */
    public Optional<Permitted> permitted(String namespaceId, String name, Caller caller) {
        return jdbc.sql("""
                SELECT s.id, s.namespace_id, s.name, s.title, s.description, s.frontmatter,
                       s.visibility, s.current_version_id,
                       (s.namespace_id = :ownNamespaceId) AS owns,
                       g.role AS granted_role
                FROM skill s
                LEFT JOIN skill_grant g
                       ON g.skill_id = s.id AND g.grantee_id = :callerId
                WHERE s.namespace_id = :namespaceId AND s.name = :name AND s.deleted_at IS NULL
                  AND (s.namespace_id = :ownNamespaceId OR g.grantee_id IS NOT NULL)
                """)
                .param("namespaceId", namespaceId)
                .param("name", name)
                .param("ownNamespaceId", caller.ownNamespaceId())
                .param("callerId", caller.userId())
                .query((rs, rowNum) -> new Permitted(
                        new SkillRow(
                                rs.getString("id"),
                                rs.getString("namespace_id"),
                                rs.getString("name"),
                                rs.getString("title"),
                                rs.getString("description"),
                                rs.getString("frontmatter"),
                                rs.getString("visibility"),
                                rs.getString("current_version_id")),
                        rs.getBoolean("owns"),
                        rs.getString("granted_role")))
                .optional();
    }

    /**
     * A skill the caller may see, and what else they may do with it (ADR 0034).
     *
     * <p><strong>Reaching this type at all already means "may read".</strong> The statement that
     * builds it refuses a row the caller has no relationship with, so there is no {@code mayRead()}
     * to answer — the three levels are {@code read}, {@link #mayWrite()} and {@link #mayAdminister()},
     * and the first is a precondition rather than a question.
     *
     * @param owns       whether the skill is in the namespace the caller owns. This is the whole of
     *                   the namespace half of the predicate, kept as a boolean because the distinction
     *                   between "mine" and "shared with me" survives past the lookup — a viewer must
     *                   not be told they may discard somebody else's draft
     * @param grantedRole {@code viewer}, {@code editor}, or null when the skill was not shared with
     *                   this caller. Kept as the column spells it rather than as a boolean, because
     *                   the two roles answer different questions
     */
    public record Permitted(SkillRow skill, boolean owns, String grantedRole) {

        /** The owner, or an {@code editor} grant. Submitting and discarding are the two things it buys. */
        public boolean mayWrite() {
            return owns || GrantRole.EDITOR.equals(grantedRole);
        }

        /**
         * The owner alone — publishing, deleting and sharing.
         *
         * <p>An {@code editor} is deliberately outside all three: publishing changes what every
         * reader of this service gets (ADR 0031 keeps it in the browser for that reason), deleting
         * takes the skill away from those readers, and re-sharing would let one grant spread without
         * the owner seeing any link in the chain (ADR 0034 §理由).
         */
        public boolean mayAdminister() {
            return owns;
        }
    }

    /**
     * Soft-deletes the live skill with this name in this namespace — <strong>the owner's only</strong>.
     *
     * <p>By name rather than by id because the address is a name (§4.1). The access predicate stays
     * inside the {@code UPDATE} for the reason {@link #find} gives for keeping it in the statement:
     * there is then no window between deciding and acting, and "not yours" and "not there" are one
     * empty result rather than two branches a later edit could pull apart.
     *
     * <p><strong>It checks the namespace half alone, where {@link #writable} also accepts an
     * {@code editor} grant.</strong> That asymmetry is the whole of "an editor may submit and may
     * discard what they submitted, and nothing else" (ADR 0034 §决定 5): deleting a skill takes away
     * what every reader of it has, and the owner is the only one who can answer for that. It is also
     * why this is written out rather than delegated — a call to {@code writable} here would compile,
     * pass anything that tested an owner, and quietly hand deletion to a grantee.
     *
     * <p>{@code RETURNING id} because the caller audits the deletion, and the audit row names the
     * skill by its identity (ADR 0004) — so a later rename cannot orphan its own trail. A by-name
     * delete that returned only a boolean would leave {@code target_id} null for every delete.
     *
     * @return the id of the skill that was deleted, or empty when no such live skill exists the
     *         caller owns
     */
    public Optional<String> softDelete(String namespaceId, String name, Caller caller, String at) {
        return jdbc.sql("""
                UPDATE skill s SET deleted_at = :at, updated_at = :at
                WHERE s.namespace_id = :namespaceId AND s.name = :name AND s.deleted_at IS NULL
                  AND s.namespace_id = :ownNamespaceId
                RETURNING s.id
                """)
                .param("at", at)
                .param("namespaceId", namespaceId)
                .param("name", name)
                .param("ownNamespaceId", caller.ownNamespaceId())
                .query(String.class)
                .optional();
    }

    /**
     * @param currentVersionId the version consumers get, or null when nothing has been published
     *                         yet. Since ADR 0031 that is an ordinary state — a skill whose versions
     *                         are all drafts — and no longer the signature of a broken invariant.
     *                         What is still broken is a pointer naming a version that is not
     *                         published; {@link VersionRepository#findCurrent} refuses that
     */
    public record SkillRow(String id, String namespaceId, String name, String title,
            String description, String frontmatter, String visibility, String currentVersionId) {
    }
}
