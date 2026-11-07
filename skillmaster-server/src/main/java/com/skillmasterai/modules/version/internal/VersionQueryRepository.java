package com.skillmasterai.modules.version.internal;

import com.skillmasterai.modules.skill.DraftProjection;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.modules.version.internal.VersionRepository.VersionRow;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The reads M7 serves lists and projections from, plus the queue's batch draft lookup. */
final class VersionQueryRepository {

    private final JdbcClient jdbc;
    private final VersionRowRepository rows;

    VersionQueryRepository(JdbcClient jdbc, VersionRowRepository rows) {
        this.jdbc = jdbc;
        this.rows = rows;
    }

    /**
     * The row a pin names, <strong>whatever its state</strong> — the consumption plane's way to tell
     * 410 from 404.
     *
     * <p>The ordinary lookups filter on {@code published}; that is right for serving, and it erases
     * the distinction the consumer needs: a revoked version is not "not there", it is "was there and
     * was taken down" (ADR 0058). Only a caller that already holds the pin can ask, and a pin is not
     * a secret — the version name was in the address they were given.
     *
     * <p>A draft or discarded row comes back too, and the caller maps it to 404: saying "this is a
     * draft" would leak something the consumption plane never shows.
     */
    Optional<VersionRow> findVisible(String skillId, VersionPin pin,
            java.util.Set<String> states) {
        if (states.isEmpty()) {
            return Optional.empty();
        }
        return rows.findByPinIgnoringState(skillId, pin)
                .filter(row -> states.contains(row.state()));
    }

    /**
     * Every version of a skill, newest first, with its state and whether the pointer names it.
     *
     * <p>Discarded versions are included. This is the author's own list, and a version they threw
     * away is a fact about the skill rather than something to hide from them — the page decides what
     * to do with it. Filtering here would also make "discard" look like "delete", which it is not.
     *
     * <p><strong>It names only M7's tables.</strong> The skill row was joined here until ADR 0053;
     * its pointer now arrives as a parameter, because M12 owns it. The caller has already
     * established that the skill exists and that this person may see it — that is what the
     * {@code Access} it came from means.
     */
    List<VersionSummary> versionsOf(String skillId, String currentVersionId) {
        return versions(skillId, currentVersionId, false);
    }

    /**
     * The author list as one rung may see it: the states that rung may read, and nothing else.
     *
     * <p>The filter lives here rather than in the caller because these are M7's rows and the set is
     * part of what a rung means. Writing/published/revoked visibility is decided by the service and
     * passed as the set — no SQL in the service, no access rules in the repository.
     */
    List<VersionSummary> versionsVisible(String skillId, String currentVersionId,
            java.util.Set<String> states) {
        if (states.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ",
                states.stream().map(state -> ":" + state).toList());
        String sql = """
                SELECT v.id, v.version, v.draft_name, v.changelog, v.digest, v.title,
                       v.file_count, v.total_bytes, v.submitted_at, v.submitted_by,
                       v.state, v.state_at, v.first_published_at, v.revoked_reason,
                       v.release_type,
                       (v.id = CAST(:currentVersionId AS text)) AS is_current
                FROM skill_version v
                WHERE v.skill_id = :skillId AND v.state IN (%s)
                ORDER BY v.number DESC
                """.formatted(placeholders);
        var spec = jdbc.sql(sql).param("skillId", skillId).param("currentVersionId", currentVersionId);
        for (String state : states) {
            spec = spec.param(state, state);
        }
        return spec.query((rs, rowNum) -> new VersionSummary(
                        rs.getString("id"),
                        rs.getString("version"),
                        rs.getString("draft_name"),
                        rs.getString("changelog"),
                        rs.getString("digest"),
                        rs.getString("title"),
                        rs.getInt("file_count"),
                        rs.getLong("total_bytes"),
                        rs.getString("submitted_at"),
                        rs.getString("submitted_by"),
                        rs.getString("state"),
                        rs.getString("state_at"),
                        rs.getString("first_published_at"),
                        rs.getString("revoked_reason"),
                        rs.getBoolean("is_current"),
                        rs.getString("release_type")))
                .list();
    }

    /**
     * The same list, narrowed to the versions a consumer may invoke.
     *
     * <p>A sibling of {@link #versionsOf} rather than a parameter on it, for the same reason M7 has
     * two snapshot entry points: the two planes ask different questions, and a boolean in the public
     * signature would let a caller reach the wider list by passing the wrong thing. What this one
     * drops is every version that is not published — a draft is not addressable at all on the
     * consumption plane (ADR 0031), and a discarded version is one its author already refused to
     * stand behind. Both remain on the author's own list, which is where they are news.
     *
     * <p><strong>The predicate is on the version row, not on the pointer.</strong> A published
     * version the pointer has moved off is precisely what a person comes here to find: it is what a
     * rollback would put back, and {@code latest} being a dist-tag rather than "the highest one"
     * (ADR 0033) means it is not otherwise discoverable from the current state.
     */
    List<VersionSummary> publishedVersionsOf(String skillId, String currentVersionId) {
        return versions(skillId, currentVersionId, true);
    }

    /**
     * @param publishedOnly adds the state predicate; the two public names above are the only callers,
     *                      so no caller decides this for itself
     */
    private List<VersionSummary> versions(String skillId, String currentVersionId,
            boolean publishedOnly) {
        // The cast is not decoration: a null parameter leaves PostgreSQL unable to infer a type on
        // either side of `=`, and a skill nothing has been published from has exactly that null.
        String sql = """
                SELECT v.id, v.version, v.draft_name, v.changelog, v.digest, v.title,
                       v.file_count, v.total_bytes, v.submitted_at, v.submitted_by,
                       v.state, v.state_at, v.first_published_at, v.revoked_reason,
                       v.release_type,
                       (v.id = CAST(:currentVersionId AS text)) AS is_current
                FROM skill_version v
                WHERE v.skill_id = :skillId
                  %s
                ORDER BY v.number DESC
                """.formatted(publishedOnly ? "AND v.state = 'published'" : "");
        return jdbc.sql(sql)
                .param("skillId", skillId)
                .param("currentVersionId", currentVersionId)
                .query((rs, rowNum) -> new VersionSummary(
                        rs.getString("id"),
                        rs.getString("version"),
                        rs.getString("draft_name"),
                        rs.getString("changelog"),
                        rs.getString("digest"),
                        rs.getString("title"),
                        rs.getInt("file_count"),
                        rs.getLong("total_bytes"),
                        rs.getString("submitted_at"),
                        rs.getString("submitted_by"),
                        rs.getString("state"),
                        rs.getString("state_at"),
                        rs.getString("first_published_at"),
                        rs.getString("revoked_reason"),
                        rs.getBoolean("is_current"),
                        rs.getString("release_type")))
                .list();
    }

    /**
     * The draft-side facts a list reads, computed here because M12 may not name
     * {@code skill_version} (ADR 0053).
     *
     * <p>One aggregate pass ordered by {@code number}, so the newest draft's name, digest, title and
     * description always come from the same row — two submissions in the same second cannot tie.
     * Empty when the skill has no drafts; the caller turns that into
     * {@link DraftProjection#empty()}.
     */
    Optional<DraftProjection> draftProjectionOf(String skillId) {
        return jdbc.sql("""
                SELECT count(*) AS drafts,
                       (array_agg(submitted_at ORDER BY number DESC))[1] AS newest_submitted_at,
                       (array_agg(version      ORDER BY number DESC))[1] AS newest_version,
                       (array_agg(digest       ORDER BY number DESC))[1] AS newest_digest,
                       (array_agg(title        ORDER BY number DESC))[1] AS newest_title,
                       (array_agg(description  ORDER BY number DESC))[1] AS newest_description
                FROM skill_version
                WHERE skill_id = :skillId AND state = 'draft'
                """)
                .param("skillId", skillId)
                .query((rs, rowNum) -> new DraftProjection(
                        rs.getInt("drafts"),
                        rs.getString("newest_submitted_at"),
                        rs.getString("newest_version"),
                        rs.getString("newest_digest"),
                        rs.getString("newest_title"),
                        rs.getString("newest_description")))
                .optional();
    }

    /**
     * Draft row ids one account submitted with one provenance string, newest first, excluding one
     * row. v50's sync uses this to drop its own superseded drafts: only rows it wrote
     * ({@code source = 'sync'}) and only for the official account are candidates.
     */
    List<String> draftsBySource(String skillId, String source, String submittedBy, String exceptId) {
        return jdbc.sql("""
                SELECT id FROM skill_version
                WHERE skill_id = :skill AND state = 'draft' AND version IS NULL
                  AND source = :source AND submitted_by = :submittedBy AND id <> :except
                ORDER BY number DESC
                """)
                .param("skill", skillId)
                .param("source", source)
                .param("submittedBy", submittedBy)
                .param("except", exceptId)
                .query((rs, rowNum) -> rs.getString("id"))
                .list();
    }

    /**
     * The drafts of several skills at once — one query, not one per skill.
     *
     * <p>A queue can hold up to five hundred skills, and asking per skill would be five hundred
     * round trips for one page. The ids come from M12's listing, which is why this takes them rather
     * than resolving a caller: the authorization question was answered there.
     *
     * <p>Newest first within each skill, which is the order the queue renders (TD §接口).
     *
     * <p><strong>Only drafts that carry no number of their own.</strong> A draft can have one — a
     * pre-v11 row, or a hand-forged one — and publishing it uses that number rather than computing
     * one (TD §算号, first branch). Such a row does not belong on a page whose whole subject is
     * "what number will this become": the page would preview a computed number and the publish
     * would ignore it. The TD says the queue does not carry them, and this is where that is true.
     */
    List<VersionRepository.DraftRow> draftsOf(Collection<String> skillIds) {
        if (skillIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT skill_id, id, draft_name, release_type, submitted_at
                FROM skill_version
                WHERE state = 'draft' AND version IS NULL AND skill_id = ANY(:skillIds)
                ORDER BY skill_id, number DESC
                """)
                .param("skillIds", skillIds.toArray(String[]::new))
                .query((rs, rowNum) -> new VersionRepository.DraftRow(
                        rs.getString("skill_id"),
                        rs.getString("id"),
                        rs.getString("draft_name"),
                        rs.getString("release_type"),
                        rs.getString("submitted_at")))
                .list();
    }
}
