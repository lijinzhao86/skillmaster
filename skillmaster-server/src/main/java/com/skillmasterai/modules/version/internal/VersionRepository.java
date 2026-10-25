package com.skillmasterai.modules.version.internal;

import com.skillmasterai.modules.skill.DraftProjection;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionSummary;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads and writes {@code skill_version} and {@code version_file} — M7's other two tables. */
public final class VersionRepository {

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
    public VersionRepository(JdbcClient jdbc, Supplier<String> ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    /**
     * Records the version unless the same content is already there under this skill.
     *
     * <p><strong>The conflict target is the digest's constraint, and nothing else.</strong>
     * {@code ON CONFLICT (skill_id, digest) DO NOTHING} keeps ADR 0005's "re-submitting identical
     * bytes produces no version" — {@code DO NOTHING} also waits on a concurrent uncommitted insert
     * of the same digest and then finds the committed row — while <em>narrowing</em> what is
     * swallowed. A primary-key collision (two rows drawn the same short code) and a
     * {@code (skill_id, number)} collision now raise {@code DuplicateKeyException} instead of
     * silently vanishing. That is deliberate (v7 TD §digest 与唯一约束): the short code is random,
     * so its collision is the one failure worth retrying, and the retry needs to see it.
     *
     * <p>{@code DO NOTHING} rather than catching the violation, for a PostgreSQL-specific reason: an
     * error there aborts the entire transaction, so a caught digest collision would leave the
     * submit unable to write anything else. A primary-key collision is different in kind — the
     * whole transaction is retried from outside by {@code DraftRecorder}, so aborting it is exactly
     * what should happen.
     *
     * <p><strong>The number is allocated here, but its serialisation comes from the caller.</strong>
     * {@code MAX(number) + 1} is safe because the submit path holds the {@code version_file} table
     * lock ({@code underExclusiveWrite}) for the whole section — not because of any lock on the skill
     * row. {@code SubmitSkillVersionUseCase} never calls {@code findOrCreate}; the table lock is what
     * serialises the allocation, and {@code SubmitSkillUseCase}'s skill row lock is taken after it.
     * A future writer that skips {@code underExclusiveWrite} breaks this, and the
     * {@code (skill_id, number)} constraint is the guard that makes the break loud rather than silent.
     *
     * <p>And deliberately not a sequence: {@code MAX(number) + 1} leaves no gap when a transaction
     * rolls back, whereas a sequence is non-transactional and would burn numbers and hand them out
     * out of order. Consuming nothing on the digest-replay branch is the same property, one case over.
     *
     * <p><strong>Which constraint refused a digest insert is not asked here.</strong> {@code DO
     * NOTHING} cannot say; the caller decides by looking, and an absent row after the statement means
     * the digest was already there. A version name never participates: {@code version} is NULL on
     * this INSERT, so {@code (skill_id, version)} cannot conflict on it.
     *
     * <p><strong>The metadata is carried on the version, not written to the skill row.</strong>
     * ADR 0031: a submission must not change anything the consumption plane sees, and the skill
     * row's title and description are what search reads. They are copied there when this version is
     * published, and only then — which is also why a version has to hold its own copy, since a later
     * submission would otherwise have overwritten the only one.
     *
     * <p>The row starts as {@code draft}: nothing is live until someone publishes it.
     *
     * @return the new version's id, or empty when identical content was already there
     */
    /**
     * Serialises content commits for one skill, and only for that skill.
     *
     * <p>The digest uniqueness check is a read followed by an UPDATE, and two drafts of the same
     * skill can both pass it and race to the unique index. PostgreSQL then aborts one transaction,
     * which would surface as a 500 instead of the 409 the other writer deserves. A transaction-scoped
     * advisory lock makes the check and the write one critical section without touching
     * {@code version_file} (submissions still take that table lock; file writes deliberately do not).
     *
     * <p>Keyed by skill id, so two skills never wait for each other, and released at commit/rollback.
     */
    public void lockContentOf(String skillId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:skillId, 0))")
                .param("skillId", skillId)
                .query((rs, rowNum) -> 0)
                .single();
    }

    public Optional<String> insertIfAbsent(String skillId, SkillMetadata metadata,
            String draftName, String note, String digest,
            int fileCount, long totalBytes, String source, String submittedBy, String at,
            String releaseType) {
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
                ON CONFLICT (skill_id, digest) DO NOTHING
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
                .optional();
    }

    /**
     * Writes the manifest, one row per file.
     *
     * <p>A statement per file rather than a set-based insert. With the ceiling at 512 files, inside
     * one transaction, on a local socket, that is a few milliseconds — and submitting is a rare
     * human action, not a hot path. Worth revisiting only if submit latency ever shows up.
     */
    public void insertFiles(String versionId, Manifest manifest) {
        for (ManifestEntry entry : manifest.entries()) {
            jdbc.sql("""
                    INSERT INTO version_file (version_id, relpath, blob_sha256, size, is_binary)
                    VALUES (:versionId, :relpath, :blobSha256, :size, :isBinary)
                    """)
                    .param("versionId", versionId)
                    .param("relpath", entry.relpath())
                    .param("blobSha256", entry.blobSha256())
                    .param("size", entry.size())
                    .param("isBinary", entry.isBinary() ? 1 : 0)
                    .update();
        }
    }

    /**
     * The row for a version of this skill, found by content.
     *
     * @param liveOnly whether only a published version counts. The consumption plane passes true,
     *                 and it is load-bearing rather than an optimisation: without it, {@code @N} of
     *                 a version submitted after the skill went live would serve content nobody
     *                 approved. Reads that have already established what they are looking at — the
     *                 submit read-back, the author's own view — pass false.
     */
    public Optional<VersionRow> findBySkillAndDigest(String skillId, String digest,
            boolean liveOnly) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND digest = :digest"
                        + " AND (NOT :liveOnly OR state = 'published')")
                .param("skillId", skillId)
                .param("digest", digest)
                .param("liveOnly", liveOnly)
                .query(VersionRepository::row)
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
    public Optional<VersionRow> findBySkillAndVersion(String skillId, String version,
            boolean liveOnly) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND version = :version"
                        + " AND (NOT :liveOnly OR state = 'published')")
                .param("skillId", skillId)
                .param("version", version)
                .param("liveOnly", liveOnly)
                .query(VersionRepository::row)
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
    public int countFor(String skillId) {
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
    public Optional<VersionRow> findCurrent(String versionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE id = :id AND state = 'published'")
                .param("id", versionId)
                .query(VersionRepository::row)
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
    public Optional<VersionRow> findNewestNotDiscarded(String skillId, boolean includeRevoked) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND state <> 'discarded'"
                        + " AND (:includeRevoked OR state <> 'revoked')"
                        + " ORDER BY number DESC LIMIT 1")
                .param("skillId", skillId)
                .param("includeRevoked", includeRevoked)
                .query(VersionRepository::row)
                .optional();
    }

    /**
     * A row by its own id — the address's {@code @<版本码>} pin (ADR 0063).
     *
     * <p>Scoped to the skill, like every other lookup here: an id from another skill's row must not
     * resolve just because it is a well-formed id. Whatever state the row is in comes back; the
     * caller decides what its rung may do with it.
     */
    public Optional<VersionRow> findByRowId(String skillId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND id = :id")
                .param("skillId", skillId)
                .param("id", id)
                .query(VersionRepository::row)
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
    public Optional<VersionRow> findByRowIdForUpdate(String skillId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND id = :id FOR UPDATE")
                .param("skillId", skillId)
                .param("id", id)
                .query(VersionRepository::row)
                .optional();
    }

    /**
     * One file row, written or replaced.
     *
     * <p>An upsert rather than delete-then-insert: the row's identity is {@code (version_id,
     * relpath)}, and replacing it in one statement is what keeps a concurrent reader from seeing
     * the file missing for the inside of a transaction. The caller has already decided whether this
     * path may be created; this method only records it.
     */
    public void putFile(String versionId, ManifestEntry entry) {
        jdbc.sql("""
                INSERT INTO version_file (version_id, relpath, blob_sha256, size, is_binary)
                VALUES (:versionId, :relpath, :blobSha256, :size, :isBinary)
                ON CONFLICT (version_id, relpath) DO UPDATE
                SET blob_sha256 = EXCLUDED.blob_sha256,
                    size = EXCLUDED.size,
                    is_binary = EXCLUDED.is_binary
                """)
                .param("versionId", versionId)
                .param("relpath", entry.relpath())
                .param("blobSha256", entry.blobSha256())
                .param("size", entry.size())
                .param("isBinary", entry.isBinary() ? 1 : 0)
                .update();
    }

    /** @return whether a row was removed */
    public int deleteFile(String versionId, String relpath) {
        return jdbc.sql("DELETE FROM version_file WHERE version_id = :versionId"
                        + " AND relpath = :relpath")
                .param("versionId", versionId)
                .param("relpath", relpath)
                .update();
    }

    /**
     * Renames a file row in place. The blob is untouched — content addressing means a move is one
     * column's value changing, not bytes being copied (v7 TD §安全边界).
     *
     * @return whether a row was written
     */
    public int renameFile(String versionId, String from, String to) {
        return jdbc.sql("UPDATE version_file SET relpath = :to"
                        + " WHERE version_id = :versionId AND relpath = :from")
                .param("versionId", versionId)
                .param("from", from)
                .param("to", to)
                .update();
    }

    /**
     * Copies a file row, sharing the source's blob.
     *
     * <p>{@code ON CONFLICT DO NOTHING} is not used: the caller has already refused a target that
     * exists ({@code file_already_exists}), so a conflict here is a bug rather than a race the
     * command can absorb — and the unique constraint says so loudly.
     */
    public void copyFile(String versionId, ManifestEntry source, String to) {
        jdbc.sql("""
                INSERT INTO version_file (version_id, relpath, blob_sha256, size, is_binary)
                VALUES (:versionId, :relpath, :blobSha256, :size, :isBinary)
                """)
                .param("versionId", versionId)
                .param("relpath", to)
                .param("blobSha256", source.blobSha256())
                .param("size", source.size())
                .param("isBinary", source.isBinary() ? 1 : 0)
                .update();
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
    public int updateContent(String versionId, String digest, int fileCount, long totalBytes,
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
    public Optional<VersionRow> findVisible(String skillId, VersionPin pin,
            java.util.Set<String> states) {
        if (states.isEmpty()) {
            return Optional.empty();
        }
        return findByPinIgnoringState(skillId, pin)
                .filter(row -> states.contains(row.state()));
    }

    public Optional<VersionRow> findByPinIgnoringState(String skillId, VersionPin pin) {
        return switch (pin) {
            case VersionPin.Named(String version) -> jdbc.sql("SELECT " + COLUMNS
                            + " FROM skill_version WHERE skill_id = :skillId AND version = :version")
                    .param("skillId", skillId)
                    .param("version", version)
                    .query(VersionRepository::row)
                    .optional();
            case VersionPin.Digest(String sha256Hex) -> jdbc.sql("SELECT " + COLUMNS
                            + " FROM skill_version WHERE skill_id = :skillId AND digest = :digest")
                    .param("skillId", skillId)
                    .param("digest", sha256Hex)
                    .query(VersionRepository::row)
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
    public Optional<VersionRow> findNewest(String skillId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId ORDER BY number DESC LIMIT 1")
                .param("skillId", skillId)
                .query(VersionRepository::row)
                .optional();
    }

    /**
     * Marks a draft published, keeping the moment it first went live.
     *
     * <p>The guard is on {@code state = 'draft'} rather than on {@code state_at IS NULL}, and the two
     * differ in what they leave behind: publishing a version that is already published — which is
     * rollback, and the gateway does it whenever its source reverts — must not move the timestamp.
     *
     * @return whether a row was written. A zero here on a version the caller read as a draft means
     *         a concurrent discard won the race, and the caller must not move the pointer
     */
    public int markPublished(String versionId, String releaseName, String at) {
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
    public int markRevoked(String versionId, String at, String reason) {
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
    public int markRestored(String versionId, String at) {
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
    public int markRolledBack(String targetId, String displacedId, String at) {
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
    public int updateDraftName(String versionId, String name) {
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
    public int updateDraftNote(String versionId, String note) {
        return jdbc.sql("UPDATE skill_version SET changelog = :note"
                        + " WHERE id = :id AND state = 'draft'")
                .param("id", versionId)
                .param("note", note == null ? "" : note)
                .update();
    }

    /**
     * One draft, as the publish queue needs it: which skill it belongs to, how it is pinned, what it
     * is called, how big it says the change is, and when it was submitted.
     *
     * @param id the row id, which is the pin — bare, with no {@code draft:} prefix (ADR 0063)
     */
    public record DraftRow(String skillId, String id, String draftName, String releaseType,
            String submittedAt) {
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
    public List<DraftRow> draftsOf(Collection<String> skillIds) {
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
                .query((rs, rowNum) -> new DraftRow(
                        rs.getString("skill_id"),
                        rs.getString("id"),
                        rs.getString("draft_name"),
                        rs.getString("release_type"),
                        rs.getString("submitted_at")))
                .list();
    }

    /**
     * Sets (or clears) the release type a draft declares. Only a draft: once published the type has
     * done its job and is history, like the name and the note (ADR 0091 §决定 1).
     *
     * @return rows written — 0 means it stopped being a draft between the read and this update
     */
    public int updateReleaseType(String versionId, String releaseType) {
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
    public int markDiscarded(String versionId, String at) {
        return jdbc.sql("""
                UPDATE skill_version SET state = 'discarded', state_at = :at
                WHERE id = :id AND state = 'draft'
                """)
                .param("id", versionId)
                .param("at", at)
                .update();
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
    public List<VersionSummary> versionsOf(String skillId, String currentVersionId) {
        return versions(skillId, currentVersionId, false);
    }

    /**
     * The author list as one rung may see it: the states that rung may read, and nothing else.
     *
     * <p>The filter lives here rather than in the caller because these are M7's rows and the set is
     * part of what a rung means. Writing/published/revoked visibility is decided by the service and
     * passed as the set — no SQL in the service, no access rules in the repository.
     */
    public List<VersionSummary> versionsVisible(String skillId, String currentVersionId,
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
    public List<VersionSummary> publishedVersionsOf(String skillId, String currentVersionId) {
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
    public Optional<DraftProjection> draftProjectionOf(String skillId) {
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
     * A version's manifest.
     *
     * <p>No {@code ORDER BY}: {@link Manifest} sorts explicitly, and it is the only thing that may
     * — the digest and the served manifest have to agree, and the database's collation is one of
     * the three orders that would disagree.
     */
    public Manifest filesOf(String versionId) {
        List<ManifestEntry> entries = jdbc.sql("""
                SELECT relpath, blob_sha256, size, is_binary
                FROM version_file WHERE version_id = :versionId
                """)
                .param("versionId", versionId)
                .query((rs, rowNum) -> new ManifestEntry(
                        rs.getString("relpath"),
                        rs.getString("blob_sha256"),
                        rs.getLong("size"),
                        rs.getInt("is_binary") != 0))
                .list();
        return Manifest.of(entries);
    }

    private static VersionRow row(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
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

    /**
     * @param number      the submission position, and only that — an ordering key that stays inside
     *                    this module (ADR 0033)
     * @param version     the name the author declared, or null when they declared none
     * @param submittedAt RFC3339 UTC, when the version was submitted. Not the same fact as
     *                    {@code stateAt}
     * @param submittedBy who submitted it. Carried because ADR 0034 lets an {@code editor} discard
     *                    <em>their own</em> drafts and nobody else's, and this is the only column
     *                    that can tell whose a draft is
     * @param state       draft | published | discarded (ADR 0031)
     * @param stateAt     when it left draft — first published, or discarded. Null while draft
     * @param title       this version's own metadata, carried here rather than read from the skill
     *                    row. A pinned read has to describe the version it pinned: serving
     *                    {@code @1}'s files under {@code @2}'s description is the same class of
     *                    mistake as serving {@code @1}'s manifest with {@code @2}'s bytes, which is
     *                    the one ADR 0012 exists to prevent
     */
    public record VersionRow(String id, int number, String version, String draftName,
            String digest, int fileCount, long totalBytes, String note, String submittedAt,
            String submittedBy, String state, String stateAt, String firstPublishedAt,
            String revokedReason, String title, String description, String frontmatter,
            String releaseType) {
    }
}
