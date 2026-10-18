package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.DraftProjection;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.skill.VersionProjection;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads and writes {@code skill} — one of the three tables M12 owns (ADR 0053). */
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
     * {@code VersionRepository#insertIfAbsent}, which depends on it and says so.
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
     * Makes a version the one consumers get, and writes every fact the lists read from the row.
     *
     * <p><strong>This is M12's write side of the seam (ADR 0053).</strong> The version's own values
     * arrive in a {@link VersionProjection}; the draft-side facts arrive in a
     * {@link DraftProjection} that M7 computed from {@code skill_version} — M12 may name only its
     * own tables, so neither is read here. This is the one statement that moves the pointer, which
     * makes it the only writer of {@code current_version_id} and of the current-version projection.
     *
     * <p><strong>{@code skill.title/description/frontmatter} are a projection of whichever version
     * is current</strong> (ADR 0031), so they move when the pointer moves and at no other time. The
     * draft side is recomputed in the same breath because the version being published has just left
     * the draft set.
     *
     * <p><strong>The pointer may move backwards.</strong> Publishing a version that is already
     * published is rollback — the gateway forces it to exist here: reverted source re-submits to the
     * same digest (ADR 0005 makes that a no-op), so the served gateway can only be brought back in
     * line by pointing at the old version again. A rollback also moves
     * {@code current_version_submitted_at} back with the pointer, which is what the list's
     * "更新于" means.
     *
     * <p><strong>{@code IS DISTINCT FROM} is what makes the return value mean something.</strong>
     * Without it an update that sets the value the row already holds still counts as one row, so a
     * request that lost a race to publish the same version would report that it moved the pointer
     * and write an audit row for a change it did not make. With it, zero rows means precisely "the
     * pointer already named this version", and nothing is written — not the timestamp, not the
     * projected metadata, and not the caller's trail.
     *
     * <p>{@code when_to_use} is extracted from the frontmatter here rather than passed in because
     * the key lives in a document M12 stores whole. The fallback to the top-level key keeps rows
     * written before V21 readable under the same rule the backfill used.
     *
     * @return whether the pointer actually moved
     */
    public boolean recordPublish(String skillId, VersionProjection version, DraftProjection drafts,
            String at) {
        return jdbc.sql("""
                UPDATE skill
                   SET current_version_id = :versionId, updated_at = :at,
                       title = :title, description = :description, frontmatter = :frontmatter,
                       when_to_use = COALESCE(
                           CAST(:frontmatter AS jsonb) #>> '{metadata,when_to_use}',
                           CAST(:frontmatter AS jsonb) ->> 'when_to_use'),
                       current_version_name = CAST(:versionName AS text),
                       current_digest = :digest,
                       current_version_submitted_at = :submittedAt,
                       draft_count = :draftCount,
                       newest_draft_submitted_at = CAST(:newestDraftSubmittedAt AS text),
                       newest_draft_version = CAST(:newestDraftVersion AS text),
                       newest_draft_digest = CAST(:newestDraftDigest AS text),
                       newest_title = CAST(:newestTitle AS text),
                       newest_description = CAST(:newestDescription AS text)
                 WHERE id = :skillId AND current_version_id IS DISTINCT FROM :versionId
                """)
                .param("skillId", skillId)
                .param("versionId", version.versionId())
                .param("at", at)
                .param("versionName", version.version())
                .param("digest", version.digest())
                .param("submittedAt", version.submittedAt())
                .param("title", version.title())
                .param("description", version.description())
                .param("frontmatter", version.frontmatterJson())
                .param("draftCount", drafts.count())
                .param("newestDraftSubmittedAt", drafts.newestSubmittedAt())
                .param("newestDraftVersion", drafts.newestVersion())
                .param("newestDraftDigest", drafts.newestDigest())
                .param("newestTitle", drafts.newestTitle())
                .param("newestDescription", drafts.newestDescription())
                .update() > 0;
    }

    /**
     * Rewrites the draft-side projection after a submission or a discard.
     *
     * <p><strong>Not {@code updated_at}.</strong> That column record the publish moment and nothing
     * else (V21); a submission changes what the draft side of the card says, not when the skill was
     * last published. The list's "更新于" reads the projected timestamps instead.
     */
    public void recordDraftState(String skillId, DraftProjection drafts) {
        jdbc.sql("""
                UPDATE skill
                   SET draft_count = :draftCount,
                       newest_draft_submitted_at = CAST(:newestDraftSubmittedAt AS text),
                       newest_draft_version = CAST(:newestDraftVersion AS text),
                       newest_draft_digest = CAST(:newestDraftDigest AS text),
                       newest_title = CAST(:newestTitle AS text),
                       newest_description = CAST(:newestDescription AS text)
                 WHERE id = :skillId
                """)
                .param("skillId", skillId)
                .param("draftCount", drafts.count())
                .param("newestDraftSubmittedAt", drafts.newestSubmittedAt())
                .param("newestDraftVersion", drafts.newestVersion())
                .param("newestDraftDigest", drafts.newestDigest())
                .param("newestTitle", drafts.newestTitle())
                .param("newestDescription", drafts.newestDescription())
                .update();
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
     * reading whether the caller owns its namespace, and reading the rung they hold all come from
     * the same join. Splitting them into a "may read" query and a "may write" query would make each
     * write path ask twice — and the second question is exactly the one a later edit forgets, which
     * after ADR 0034 means a viewer who can delete. The grant is a {@code LEFT JOIN} rather than the
     * correlated {@code EXISTS} it could be, because the role is wanted, not just its presence.
     *
     * <p><strong>The rung is the higher of two sources</strong> (ADR 0048): what was granted to
     * this person, and what the skill's {@code public_rung} hands to everybody. Neither overrides
     * the other; a person's effective standing is the higher of the two. That is why the public
     * branch is in the <em>predicate</em> as well as in the answer — a public skill the caller was
     * never granted still has to produce a row, or it would be visible to search and invisible to
     * every read.
     *
     * <p>Both tables named here are M12's, so rule 1 is untouched and the lookup stays a comparison
     * of values this layer already holds rather than a join across a module boundary.
     *
     * @return empty when there is no such live skill, <em>or</em> when there is one this caller may
     *         not see — one answer for both, deliberately; see the class note on
     *         {@code SkillVersionService}'s 404s
     */
    public Optional<Permitted> permitted(String namespaceId, String name, Caller caller) {
        return jdbc.sql("""
                SELECT s.id, s.namespace_id, s.name, s.title, s.description, s.frontmatter,
                       s.visibility, s.public_rung, s.current_version_id,
                       (s.namespace_id = :ownNamespaceId) AS owns,
                       g.role AS granted_role
                FROM skill s
                LEFT JOIN skill_grant g
                       ON g.skill_id = s.id AND g.grantee_id = :callerId
                WHERE s.namespace_id = :namespaceId AND s.name = :name AND s.deleted_at IS NULL
                  AND (s.namespace_id = :ownNamespaceId
                       OR g.grantee_id IS NOT NULL
                       -- V19's CHECK makes `public` imply a non-null rung, so this one condition
                       -- cannot let through a public row that hands out nothing.
                       OR s.visibility = 'public')
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
                                rs.getString("public_rung"),
                                rs.getString("current_version_id")),
                        rs.getBoolean("owns"),
                        // ADR 0048: the two sources never overwrite each other, and what the person
                        // can actually do has one answer — the higher one.
                        GrantRole.higher(rs.getString("granted_role"),
                                rs.getString("public_rung"))))
                .optional();
    }

    /**
     * A skill the caller may see, and the rung they hold on it (ADR 0034, ADR 0047).
     *
     * <p><strong>Reaching this type at all already means "may read".</strong> The statement that
     * builds it refuses a row the caller has no relationship with, so there is no {@code mayRead()}
     * to answer — reading is a precondition, and every rung is asked for through
     * {@link #atLeast(String)}.
     *
     * @param owns whether the skill is in the namespace the caller owns. This is the namespace half
     *             of the predicate, kept as a boolean because the distinction between "mine" and
     *             "shared with me" survives past the lookup — nobody may discard a draft that is
     *             not theirs merely by holding a rung, and only the owner may delete
     * @param rung the effective rung — the higher of the grant and the public rung (ADR 0048), or
     *             null when the caller owns the skill and neither source applies. Kept as the
     *             column spells it rather than as a boolean, because "every rung includes the ones
     *             below" needs a value to compare
     */
    public record Permitted(SkillRow skill, boolean owns, String rung) {

        /**
         * Whether the caller is at or above {@code required}.
         *
         * <p>The owner is above the ladder rather than on it, so ownership answers yes to every
         * rung — including {@code admin}, which is why "may administer" and "owns" had to be
         * separated at the deletion path (ADR 0047 §决定 4).
         */
        public boolean atLeast(String required) {
            return owns || GrantRole.atLeast(rung, required);
        }
    }

    /**
     * Soft-deletes the live skill with this id.
     *
     * <p><strong>By id, and the ownership check is the caller's.</strong> Since ADR 0053 the delete
     * path resolves the address through {@code accessFor} first — that is where "reachable" and
     * "the owner" are decided, with different answers — so repeating a namespace predicate here
     * would be a second, silently divergent copy of the rule. What this statement guarantees is the
     * other half: a skill that is already deleted stays deleted, and the row it touched is named by
     * its identity (ADR 0004) for the audit trail.
     *
     * @return the deleted skill's id, or empty when it was already deleted
     */
    public Optional<String> softDelete(String skillId, String at) {
        return jdbc.sql("""
                UPDATE skill SET deleted_at = :at, updated_at = :at
                WHERE id = :skillId AND deleted_at IS NULL
                RETURNING id
                """)
                .param("skillId", skillId)
                .param("at", at)
                .query(String.class)
                .optional();
    }

    /**
     * Clears {@code deleted_at} on the skill this address names — <strong>the owner's only, and the
     * only by-address path that resolves a deleted row</strong> (ADR 0050).
     *
     * <p>It cannot use {@link #permitted}: that predicate carries {@code deleted_at IS NULL}, so
     * reusing it would make restore answer 404 for ever. It is deliberately narrow in the other
     * direction too — the address segment is part of the {@code WHERE}, and ownership is checked in
     * the same statement rather than by a caller afterwards, so a non-owner's attempt is the same
     * empty result as an address that never existed. That is the answer the 404 page needs: a
     * deleted skill must stay indistinguishable from one that was never there.
     *
     * @return the restored skill's id, or empty when no deleted skill of this name the caller owns
     */
    public Optional<String> restore(String namespaceId, String name, Caller caller, String at) {
        return jdbc.sql("""
                UPDATE skill s SET deleted_at = NULL, updated_at = :at
                WHERE s.namespace_id = :namespaceId AND s.name = :name
                  AND s.deleted_at IS NOT NULL
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
     * Writes the two columns that are one fact: whether the skill is public, and which rung
     * strangers get (ADR 0048).
     *
     * <p>The pair travels together because V19's CHECK refuses any other combination; writing one
     * without the other is not a state the database will hold. The caller has already decided that
     * the caller may administer this skill.
     */
    public void setVisibility(String skillId, String visibility, String publicRung, String at) {
        jdbc.sql("""
                UPDATE skill
                   SET visibility = :visibility, public_rung = CAST(:publicRung AS text),
                       updated_at = :at
                 WHERE id = :skillId
                """)
                .param("skillId", skillId)
                .param("visibility", visibility)
                .param("publicRung", publicRung)
                .param("at", at)
                .update();
    }

    /**
     * @param currentVersionId the version consumers get, or null when nothing has been published
     *                         yet. Since ADR 0031 that is an ordinary state — a skill whose versions
     *                         are all drafts — and no longer the signature of a broken invariant.
     *                         What is still broken is a pointer naming a version that is not
     *                         published; {@link VersionRepository#findCurrent} refuses that
     */
    public record SkillRow(String id, String namespaceId, String name, String title,
            String description, String frontmatter, String visibility, String publicRung,
            String currentVersionId) {
    }
}
