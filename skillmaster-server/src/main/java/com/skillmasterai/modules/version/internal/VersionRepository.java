package com.skillmasterai.modules.version.internal;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillMetadata;
import com.skillmasterai.modules.version.VersionSummary;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads and writes {@code skill_version} and {@code version_file} — M7's other two tables. */
public final class VersionRepository {

    private static final String COLUMNS = "id, number, digest, file_count, total_bytes, submitted_at,"
            + " state, state_at, title, description, frontmatter";

    private final JdbcClient jdbc;

    public VersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the version unless the same content is already there under this skill.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than catching the unique violation, for a reason
     * specific to PostgreSQL: an error there aborts the entire transaction, so a caught violation
     * would leave the submit unable to write anything else. {@code DO NOTHING} also waits on a
     * concurrent uncommitted insert of the same digest and then correctly finds the committed row.
     * This is ADR 0005's "幂等由唯一约束直接实现，不需要先查再写" made concrete — and ADR 0031 keeps
     * it: re-submitting identical bytes produces no version, draft or otherwise.
     *
     * <p><strong>The number is allocated here, but its serialisation comes from the caller.</strong>
     * {@code MAX(number) + 1} in the statement below is safe only because the submit path takes the
     * {@code skill} row's lock first: {@link SkillRepository#findOrCreate}'s {@code ON CONFLICT … DO
     * UPDATE} locks that row before its {@code WHERE} is even evaluated, so the lock is held even
     * when the update is filtered out. Two concurrent submits of one skill therefore queue on it and
     * cannot interleave. That is an <em>unenforced</em> convention — a second writer that skips
     * {@code findOrCreate}, or a raised isolation level (the subquery would read a stale snapshot),
     * breaks it. It breaks loudly rather than quietly: whatever the failure, it arrives as a SQL
     * error rather than as two contents sharing a number.
     *
     * <p>Deliberately <em>not</em> {@code ON CONFLICT (skill_id, number) DO NOTHING}: that would turn
     * a broken invariant into silent aliasing — two contents sharing one number, which is exactly
     * the property ADR 0012 says {@code @3} must never lose. A constraint violation here is the
     * correct alarm, so it is left to surface as one.
     *
     * <p>And deliberately not a sequence: {@code MAX(number) + 1} leaves no gap when a transaction
     * rolls back, whereas a sequence is non-transactional and would burn numbers and hand them out
     * out of order. Consuming nothing on the conflict branch is the same property, one case over.
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
    public Optional<String> insertIfAbsent(String skillId, SkillMetadata metadata, String digest,
            int fileCount, long totalBytes, String source, String submittedBy, String at) {
        return jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, digest, file_count, total_bytes,
                                           changelog, source, submitted_by, submitted_at,
                                           state, state_at, title, description, frontmatter)
                VALUES (:id, :skillId,
                        (SELECT COALESCE(MAX(number), 0) + 1 FROM skill_version
                         WHERE skill_id = :skillId),
                        :digest, :fileCount, :totalBytes,
                        '', :source, :submittedBy, :at,
                        'draft', NULL, :title, :description, :frontmatter)
                ON CONFLICT (skill_id, digest) DO NOTHING
                RETURNING id
                """)
                .param("id", Ulid.generate())
                .param("skillId", skillId)
                .param("digest", digest)
                .param("fileCount", fileCount)
                .param("totalBytes", totalBytes)
                .param("source", source)
                .param("submittedBy", submittedBy)
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
     * A version by its immutable alias — §4.1's {@code @3}.
     *
     * <p>A number is not an identity (the digest is), but {@code UNIQUE (skill_id, number)} makes it
     * a stable name for one piece of content, which is what an address needs. It is resolved through
     * the skill rather than globally because numbers are per-skill: {@code @3} means the third
     * version of <em>this</em> skill, and {@code @3} of another skill is unrelated content.
     *
     * @param liveOnly as in {@link #findBySkillAndDigest}
     */
    public Optional<VersionRow> findBySkillAndNumber(String skillId, int number, boolean liveOnly) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND number = :number"
                        + " AND (NOT :liveOnly OR state = 'published')")
                .param("skillId", skillId)
                .param("number", number)
                .param("liveOnly", liveOnly)
                .query(VersionRepository::row)
                .optional();
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
    public Optional<VersionRow> findNewestNotDiscarded(String skillId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND state <> 'discarded'"
                        + " ORDER BY number DESC LIMIT 1")
                .param("skillId", skillId)
                .query(VersionRepository::row)
                .optional();
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
    public int markPublished(String versionId, String at) {
        return jdbc.sql("""
                UPDATE skill_version SET state = 'published', state_at = COALESCE(state_at, :at)
                WHERE id = :id AND state = 'draft'
                """)
                .param("id", versionId)
                .param("at", at)
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
     * <p>An empty list is not "no such skill": the namespace predicate makes it "no such skill
     * <em>here</em>", and a skill with no versions cannot exist (submitting writes both rows in one
     * transaction). The caller establishes existence separately, by resolving a snapshot.
     */
    public List<VersionSummary> versionsOf(String namespaceId, String name) {
        return jdbc.sql("""
                SELECT v.number, v.digest, v.file_count, v.total_bytes, v.submitted_at,
                       v.state, v.state_at, (v.id = s.current_version_id) AS is_current
                FROM skill_version v
                JOIN skill s ON s.id = v.skill_id
                WHERE s.namespace_id = :namespaceId AND s.name = :name AND s.deleted_at IS NULL
                ORDER BY v.number DESC
                """)
                .param("namespaceId", namespaceId)
                .param("name", name)
                .query((rs, rowNum) -> new VersionSummary(
                        rs.getInt("number"),
                        rs.getString("digest"),
                        rs.getInt("file_count"),
                        rs.getLong("total_bytes"),
                        rs.getString("submitted_at"),
                        rs.getString("state"),
                        rs.getString("state_at"),
                        rs.getBoolean("is_current")))
                .list();
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
                rs.getString("digest"),
                rs.getInt("file_count"),
                rs.getLong("total_bytes"),
                rs.getString("submitted_at"),
                rs.getString("state"),
                rs.getString("state_at"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("frontmatter"));
    }

    /**
     * @param submittedAt RFC3339 UTC, when the version was submitted. Not the same fact as
     *                    {@code stateAt}
     * @param state       draft | published | discarded (ADR 0031)
     * @param stateAt     when it left draft — first published, or discarded. Null while draft
     * @param title       this version's own metadata, carried here rather than read from the skill
     *                    row. A pinned read has to describe the version it pinned: serving
     *                    {@code @1}'s files under {@code @2}'s description is the same class of
     *                    mistake as serving {@code @1}'s manifest with {@code @2}'s bytes, which is
     *                    the one ADR 0012 exists to prevent
     */
    public record VersionRow(String id, int number, String digest, int fileCount, long totalBytes,
            String submittedAt, String state, String stateAt, String title, String description,
            String frontmatter) {
    }
}
