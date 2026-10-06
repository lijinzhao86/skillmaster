package com.skillmasterai.modules.version.internal;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.version.SkillMetadata;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads and writes {@code skill} — one of the three tables M7 owns. */
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
     * The skill with this name in this namespace, if there is one.
     *
     * <p><strong>"Live" here means only "not soft-deleted", and since ADR 0031 that is a weaker
     * statement than it used to be.</strong> A skill can now exist with nothing published from it,
     * so this row may have no current version at all; whether anything is readable is a question
     * about its versions, and it is asked separately and per plane. What this method answers is
     * just "does a skill of this name exist here for the caller to act on".
     *
     * <p>{@code UNIQUE(namespace_id, name)} makes this at most one row. Every read resolves its skill
     * this way, because the address is a name rather than an id (§4.1) — the gateway included, since
     * its id is not known until it has been published once.
     *
     * <p><strong>The namespace predicate is the whole authorization check, and that is the point
     * of putting it here.</strong> §4.2 requires an unreadable private skill to be a 404 rather
     * than a 403, because a 403 confirms it exists. Filtering in the same statement that finds the
     * row makes "not yours" and "not there" the same empty result <em>structurally</em>, rather
     * than two branches that a later edit could drift apart — there is no separate predicate to
     * forget, and nothing to render as a 403.
     *
     * <p>The caller supplies the namespace, so this is not a general "may I see it" query: M7
     * knows which namespace a skill is in, M4 knows which namespace the caller owns, and the
     * use-case layer is where those two facts are allowed to meet (§2.5). The use case does filter
     * on the address's first segment as well, but that gate is redundant with this one and is not
     * what the rule rests on.
     */
    public java.util.Optional<SkillRow> byName(String namespaceId, String name) {
        return jdbc.sql("""
                SELECT id, namespace_id, name, title, description, frontmatter, visibility,
                       current_version_id
                FROM skill
                WHERE namespace_id = :namespaceId AND name = :name AND deleted_at IS NULL
                """)
                .param("namespaceId", namespaceId)
                .param("name", name)
                .query((rs, rowNum) -> new SkillRow(
                        rs.getString("id"),
                        rs.getString("namespace_id"),
                        rs.getString("name"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("frontmatter"),
                        rs.getString("visibility"),
                        rs.getString("current_version_id")))
                .optional();
    }

    /**
     * Soft-deletes the live skill with this name in this namespace.
     *
     * <p>By name rather than by id because the address is a name (§4.1). The namespace predicate
     * stays inside the {@code UPDATE} for the reason {@link #byName} gives for keeping it in its
     * own statement: there is then no window between deciding and acting, and "not yours" and "not
     * there" are one empty result rather than two branches a later edit could pull apart.
     *
     * <p>{@code RETURNING id} because the caller audits the deletion, and the audit row names the
     * skill by its identity (ADR 0004) — so a later rename cannot orphan its own trail. A by-name
     * delete that returned only a boolean would leave {@code target_id} null for every delete.
     *
     * @return the id of the skill that was deleted, or empty when no such live skill exists
     */
    public Optional<String> softDelete(String namespaceId, String name, String at) {
        return jdbc.sql("""
                UPDATE skill SET deleted_at = :at, updated_at = :at
                WHERE namespace_id = :namespaceId AND name = :name AND deleted_at IS NULL
                RETURNING id
                """)
                .param("at", at)
                .param("namespaceId", namespaceId)
                .param("name", name)
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
