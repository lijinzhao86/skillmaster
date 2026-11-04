package com.skillmasterai.modules.version.internal;

import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.internal.VersionRepository.VersionRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code skill_version} inserts, lookups and state updates — the row half of M7's storage. */
final class VersionRowRepository {

    private static final String COLUMNS = "id, number, version, draft_name, digest, file_count,"
            + " total_bytes, changelog, submitted_at, submitted_by, state, state_at,"
            + " first_published_at, revoked_reason, title, description, frontmatter, release_type";

    private final JdbcClient jdbc;
    private final Supplier<String> ids;

    /**
     * @param ids the source of a new row's id. A seam rather than a static call so a test can
     *            inject a colliding id and prove {@code DraftRecorder} retries; production passes
     *            {@link com.skillmasterai.common.ShortCode#generate}
     */
    VersionRowRepository(JdbcClient jdbc, Supplier<String> ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    /**
     * Inserts the version row and returns its id.
     *
     * <p><strong>No {@code ON CONFLICT}.</strong> The content index covers only non-draft rows
     * (ADR 0135), so a draft insert can collide only on its id or its per-skill number — both mean
     * "draw again", so the duplicate key is left to {@code DraftRecorder} to retry. Swallowing it
     * returned an id no caller could act on.
     *
     * <p>{@code MAX(number) + 1} is serialised by the caller's table lock
     * ({@code underExclusiveWrite}); the {@code (skill_id, number)} constraint makes a writer that
     * skips it fail loudly. Not a sequence: a rollback would burn a number.
     *
     * <p>The metadata is carried on the version, not the skill row (ADR 0031); it is copied over on
     * publish, which is why the version has to hold its own copy.
     *
     * @param ids the source of a new row's id — a seam so a test can inject a collision.
     */
    String insertIfAbsent(String skillId, SkillMetadata metadata,
            String draftName, String note, String digest,
            int fileCount, long totalBytes, String source, String submittedBy, String at,
            String releaseType) {
        // **No `ON CONFLICT`, deliberately.** The content index no longer covers drafts (ADR
        // 0135), so the only collision these rows can hit is the row id or the per-skill number —
        // both of which mean "draw again", not "this content already exists". Swallowing them
        // returned an empty id that no caller could act on; letting the duplicate key out is what
        // makes DraftRecorder re-run the submission with a fresh draw.
        return jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, version, draft_name, digest,
                                           file_count, total_bytes, changelog, source,
                                           submitted_by, submitted_at, state, state_at,
                                           title, description, frontmatter, release_type)
                VALUES (:id, :skillId,
                        (SELECT COALESCE(MAX(number), 0) + 1 FROM skill_version
                         WHERE skill_id = :skillId),
                        NULL, :draftName, :digest, :fileCount, :totalBytes,
                        :note, :source, :submittedBy, :at,
                        'draft', NULL, :title, :description, :frontmatter, :releaseType)
                RETURNING id
                """)
                .param("id", ids.get())
                .param("skillId", skillId)
                .param("draftName", draftName)
                .param("note", note == null ? "" : note)
                .param("digest", digest)
                .param("fileCount", fileCount)
                .param("totalBytes", totalBytes)
                .param("source", source)
                .param("submittedBy", submittedBy)
                .param("releaseType", releaseType)
                .param("at", at)
                .param("title", metadata.title())
                .param("description", metadata.description())
                .param("frontmatter", metadata.frontmatterJson())
                .query(String.class)
                .single();
    }

    /**
     * The row for a version of this skill, found by content.
     *
     * <p>{@code liveOnly} is the consumption plane's guard, not an optimisation: without it
     * {@code @N} could serve content nobody published. Reads that already know what they are
     * looking at pass false.
     */
    Optional<VersionRow> findBySkillAndDigest(String skillId, String digest,
            boolean liveOnly) {
        // Drafts may share content (ADR 0135), so one digest can name several rows. This order
        // makes the answer deterministic: live, then a draft, then revoked or discarded.
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND digest = :digest"
                        + " AND (NOT :liveOnly OR state = 'published')"
                        + " ORDER BY CASE state WHEN 'published' THEN 0 WHEN 'draft' THEN 1"
                        + "          ELSE 2 END, submitted_at DESC, id"
                        + " LIMIT 1")
                .param("skillId", skillId)
                .param("digest", digest)
                .param("liveOnly", liveOnly)
                .query(VersionRowRepository::row)
                .optional();
    }

    /**
     * This submitter's own draft holding the given content, or empty.
     *
     * <p>Replay is per author now that drafts may share content (ADR 0135); somebody else's draft is
     * not "you submitted this already".
     */
    Optional<VersionRow> findDraftBySkillAndDigestAndSubmitter(String skillId, String digest,
            String submittedBy) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND digest = :digest"
                        + " AND state = 'draft' AND submitted_by = :submittedBy"
                        + " ORDER BY submitted_at DESC, id LIMIT 1")
                .param("skillId", skillId)
                .param("digest", digest)
                .param("submittedBy", submittedBy)
                .query(VersionRowRepository::row)
                .optional();
    }

    /**
     * A non-draft row holding this content, or empty.
     *
     * <p>Drafts are exempt from content uniqueness (ADR 0135), so the only rows a publish can
     * collide with are the non-draft ones the partial unique index covers.
     */
    Optional<VersionRow> findNonDraftBySkillAndDigest(String skillId, String digest) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND digest = :digest"
                        + " AND state <> 'draft'")
                .param("skillId", skillId)
                .param("digest", digest)
                .query(VersionRowRepository::row)
                .optional();
    }

    /**
     * A version by the name its author gave it — §4.1's {@code @1.2.3}.
     *
     * <p>A name is not an identity (the digest is), but {@code UNIQUE (skill_id, version)} makes it a
     * stable label for one piece of content, which is what an address needs. It is resolved through
     * the skill rather than globally because names are per-skill: {@code @1.0.0} means that version
     * of <em>this</em> skill, and {@code @1.0.0} of another skill is unrelated content.
     *
     * <p>Never reaches a nameless version, and cannot be made to: {@code version} is NULL there, and
     * {@code = NULL} matches nothing — which is the right answer, because a version with no name has
     * no name to be addressed by (ADR 0033).
     *
     * @param liveOnly as in {@link #findBySkillAndDigest}
     */
    Optional<VersionRow> findBySkillAndVersion(String skillId, String version,
            boolean liveOnly) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND version = :version"
                        + " AND (NOT :liveOnly OR state = 'published')")
                .param("skillId", skillId)
                .param("version", version)
                .param("liveOnly", liveOnly)
                .query(VersionRowRepository::row)
                .optional();
    }

    /**
     * How many versions this skill has, of any state.
     *
     * <p>Asked by the submit path for one reason: to tell a first submission from a later one. That
     * used to be readable from the version number ({@code number == 1}), and the number has left the
     * wire (ADR 0033), so the fact has to be computed. Counting after the insert is exact rather than
     * approximate — versions are never deleted — and needs none of the {@code xmax} trickery that
     * asking the upsert whether it inserted would.
     */
    int countFor(String skillId) {
        return jdbc.sql("SELECT count(*) FROM skill_version WHERE skill_id = :skillId")
                .param("skillId", skillId)
                .query(Integer.class)
                .single();
    }

    /**
     * The version a skill's pointer names, <strong>requiring it to be published</strong>.
     *
     * <p>The requirement is what turns a broken pointer into a loud failure rather than a served
     * draft: {@code current_version_id} is written only by publishing, which marks the version
     * published in the same transaction, so a pointer naming anything else cannot come from this
     * application. Answering 404 would blame the caller for our own corruption.
     */
    Optional<VersionRow> findCurrent(String versionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE id = :id AND state = 'published'")
                .param("id", versionId)
                .query(VersionRowRepository::row)
                .optional();
    }

    /**
     * The most recently submitted version that has not been discarded.
     *
     * <p>For the author's view of a skill nothing has been published from — the pointer is null and
     * there is still something to look at (ADR 0031). Highest number rather than latest timestamp,
     * because numbers are allocated in submission order and two submissions in the same second
     * would otherwise tie.
     */
    Optional<VersionRow> findNewestNotDiscarded(String skillId, boolean includeRevoked) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND state <> 'discarded'"
                        + " AND (:includeRevoked OR state <> 'revoked')"
                        + " ORDER BY number DESC LIMIT 1")
                .param("skillId", skillId)
                .param("includeRevoked", includeRevoked)
                .query(VersionRowRepository::row)
                .optional();
    }

    /**
     * A row by its own id — the address's {@code @<版本码>} pin (ADR 0063).
     *
     * <p>Scoped to the skill, like every other lookup here: an id from another skill's row must not
     * resolve just because it is a well-formed id. Whatever state the row is in comes back; the
     * caller decides what its rung may do with it.
     */
    Optional<VersionRow> findByRowId(String skillId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND id = :id")
                .param("skillId", skillId)
                .param("id", id)
                .query(VersionRowRepository::row)
                .optional();
    }

    /**
     * The same row, locked — the first statement of a draft write's transaction.
     *
     * <p><strong>Taken after the gates, never before them.</strong> A caller that may not see the
     * row would otherwise queue behind every writer of a version it cannot read (v7 TD §并发). By
     * the time control reaches here the rung, the state's visibility and ownership have all been
     * decided, so the lock is held only by writers who are actually going to write.
     */
    Optional<VersionRow> findByRowIdForUpdate(String skillId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND id = :id FOR UPDATE")
                .param("skillId", skillId)
                .param("id", id)
                .query(VersionRowRepository::row)
                .optional();
    }

    /**
     * Rewrites the facts a content change moves: the digest, the two counts, and — when the write
     * touched the root {@code SKILL.md} — the version's own metadata snapshot.
     *
     * <p>Guarded on {@code state = 'draft'}. Published content is frozen (v7 TD §行的可变性), and
     * this guard is that rule expressed where the row is written rather than only where it is
     * checked.
     *
     * @param metadata the new title/description/frontmatter, or null when the bytes written were not
     *                 the root {@code SKILL.md}
     * @return whether a row was written
     */
    int updateContent(String versionId, String digest, int fileCount, long totalBytes,
            SkillMetadata metadata) {
        return jdbc.sql("""
                UPDATE skill_version
                SET digest = :digest, file_count = :fileCount, total_bytes = :totalBytes,
                    title = COALESCE(CAST(:title AS text), title),
                    description = COALESCE(CAST(:description AS text), description),
                    frontmatter = COALESCE(CAST(:frontmatter AS text), frontmatter)
                WHERE id = :id AND state = 'draft'
                """)
                .param("id", versionId)
                .param("digest", digest)
                .param("fileCount", fileCount)
                .param("totalBytes", totalBytes)
                .param("title", metadata == null ? null : metadata.title())
                .param("description", metadata == null ? null : metadata.description())
                .param("frontmatter", metadata == null ? null : metadata.frontmatterJson())
                .update();
    }

    Optional<VersionRow> findByPinIgnoringState(String skillId, VersionPin pin) {
        return switch (pin) {
            case VersionPin.Named(String version) -> jdbc.sql("SELECT " + COLUMNS
                            + " FROM skill_version WHERE skill_id = :skillId AND version = :version")
                    .param("skillId", skillId)
                    .param("version", version)
                    .query(VersionRowRepository::row)
                    .optional();
            case VersionPin.Digest(String sha256Hex) -> jdbc.sql("SELECT " + COLUMNS
                            + " FROM skill_version WHERE skill_id = :skillId AND digest = :digest")
                    .param("skillId", skillId)
                    .param("digest", sha256Hex)
                    .query(VersionRowRepository::row)
                    .optional();
            case VersionPin.RowId(String id) -> findByRowId(skillId, id);
            case VersionPin.Latest() -> Optional.empty();
        };
    }

    /**
     * The most recently submitted version, whatever was decided about it.
     *
     * <p>The second half of the author's fallback above, for the one skill it does not cover: a skill
     * whose every version was discarded. That skill is still in the author's listing — it is their
     * work, and "everything here was thrown away" is a fact about it rather than a reason to hide it —
     * and the row links to an address that has to resolve to something. Without this the link led to
     * a 404, so the page said the skill did not exist while the list was showing it.
     *
     * <p>Not for the consumption plane, and not reachable from it: that plane asks only through
     * {@code liveSnapshot} or {@code findCurrent}, both of which require `published`.
     */
    Optional<VersionRow> findNewest(String skillId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId ORDER BY number DESC LIMIT 1")
                .param("skillId", skillId)
                .query(VersionRowRepository::row)
                .optional();
    }

    /**
     * Marks a draft published, keeping the moment it first went live.
     *
     * <p>Only a row still in {@code draft} moves: {@code first_published_at} is the moment the
     * version first went live, so a later state change must never rewrite it. The guard is written
     * as {@code state = 'draft'} rather than {@code state_at IS NULL}; the two are the same
     * predicate (V10's {@code skill_version_state_at_paired} pins them together), and the state
     * column is what a reader of this statement is actually asking about.
     *
     * <p><strong>Rollback does not come through here.</strong> Re-publishing an older version is
     * {@link #markRolledBack}, which is allowed to move {@code state_at} because the version's
     * state really did change again.
     *
     * @return whether a row was written. A zero here on a version the caller read as a draft means
     *         a concurrent discard or publish won the race, and the caller must not move the pointer
     */
    int markPublished(String versionId, String releaseName, String at) {
        return jdbc.sql("""
                UPDATE skill_version
                SET state = 'published',
                    version = COALESCE(version, :releaseName),
                    state_at = COALESCE(state_at, :at),
                    first_published_at = COALESCE(first_published_at, :at)
                WHERE id = :id AND state = 'draft'
                """)
                .param("id", versionId)
                .param("releaseName", releaseName)
                .param("at", at)
                .update();
    }

    /**
     * Takes a published, non-current version down without deleting it (ADR 0058).
     *
     * @param reason optional; NULL when the maintain wrote none (ADR 0059)
     * @return whether a row was written. Zero means the row is not a published one any more — a
     *         concurrent revoke or restore won the race, and the caller reports the conflict
     */
    int markRevoked(String versionId, String at, String reason) {
        return jdbc.sql("""
                UPDATE skill_version
                SET state = 'revoked', state_at = :at, revoked_reason = :reason
                WHERE id = :id AND state = 'published'
                """)
                .param("id", versionId)
                .param("at", at)
                .param("reason", reason)
                .update();
    }

    /**
     * Puts a revoked version back among the readable ones. It does not become current: restoring
     * returns it to history, and a maintain still has to publish it to move the pointer.
     *
     * @return whether a row was written
     */
    int markRestored(String versionId, String at) {
        return jdbc.sql("""
                UPDATE skill_version
                SET state = 'published', state_at = :at, revoked_reason = NULL
                WHERE id = :id AND state = 'revoked'
                """)
                .param("id", versionId)
                .param("at", at)
                .update();
    }

    /**
     * Moves the "published" label between two rows in one statement: the rollback target and the
     * version it displaces. Both are already published, so only {@code state_at} moves — and both
     * move, because the design's 「最近状态变更」 is a fact about each row.
     *
     * <p><strong>Exactly two rows, or nothing.</strong> One match means one of the two changed
     * state concurrently (revoked between the read and this write); reporting success then would
     * leave the other row's timestamp stale and the page claiming a move that half happened.
     *
     * @return the number of rows written; the caller requires 2
     */
    int markRolledBack(String targetId, String displacedId, String at) {
        return jdbc.sql("""
                UPDATE skill_version SET state_at = :at
                WHERE id IN (:targetId, :displacedId) AND state = 'published'
                """)
                .param("targetId", targetId)
                .param("displacedId", displacedId)
                .param("at", at)
                .update();
    }

    /**
     * Replaces a draft's name. The guard is the state, not the caller: permission is the service's
     * to decide, and freezing after publish is this table's fact.
     *
     * @return whether a row was written; zero on a version that is no longer a draft
     */
    int updateDraftName(String versionId, String name) {
        return jdbc.sql("UPDATE skill_version SET draft_name = :name"
                        + " WHERE id = :id AND state = 'draft'")
                .param("id", versionId)
                .param("name", name)
                .update();
    }

    /**
     * Replaces a draft's note, guarded the same way.
     *
     * <p>The column is {@code NOT NULL} ({@code V1__baseline.sql:387}), so clearing a note writes
     * the empty string and the wire layer reports that as {@code null}. Writing SQL {@code NULL}
     * here is a constraint violation, not "no note".
     */
    int updateDraftNote(String versionId, String note) {
        return jdbc.sql("UPDATE skill_version SET changelog = :note"
                        + " WHERE id = :id AND state = 'draft'")
                .param("id", versionId)
                .param("note", note == null ? "" : note)
                .update();
    }

    /**
     * Sets (or clears) the release type a draft declares. Only a draft: once published the type has
     * done its job and is history, like the name and the note (ADR 0091 §决定 1).
     *
     * @return rows written — 0 means it stopped being a draft between the read and this update
     */
    int updateReleaseType(String versionId, String releaseType) {
        return jdbc.sql("UPDATE skill_version SET release_type = :releaseType"
                        + " WHERE id = :id AND state = 'draft'")
                .param("id", versionId)
                .param("releaseType", releaseType)
                .update();
    }

    /**
     * Marks a draft discarded. One-way: nothing brings a discarded version back.
     *
     * <p>Guarded the same way {@link #markPublished} is, so a version that has just been published
     * cannot be discarded on a stale read.
     *
     * @return whether a row was written
     */
    int markDiscarded(String versionId, String at) {
        return jdbc.sql("""
                UPDATE skill_version SET state = 'discarded', state_at = :at
                WHERE id = :id AND state = 'draft'
                """)
                .param("id", versionId)
                .param("at", at)
                .update();
    }

    static VersionRow row(ResultSet rs, int rowNum) throws SQLException {
        return new VersionRow(
                rs.getString("id"),
                rs.getInt("number"),
                rs.getString("version"),
                rs.getString("draft_name"),
                rs.getString("digest"),
                rs.getInt("file_count"),
                rs.getLong("total_bytes"),
                rs.getString("changelog"),
                rs.getString("submitted_at"),
                rs.getString("submitted_by"),
                rs.getString("state"),
                rs.getString("state_at"),
                rs.getString("first_published_at"),
                rs.getString("revoked_reason"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("frontmatter"),
                rs.getString("release_type"));
    }
}
