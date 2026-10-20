package com.skillmasterai.modules.version.internal;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.skill.DraftProjection;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionSummary;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads and writes {@code skill_version} and {@code version_file} — M7's other two tables. */
public final class VersionRepository {

    private static final String COLUMNS = "id, number, version, draft_name, digest, file_count,"
            + " total_bytes, changelog, submitted_at, submitted_by, state, state_at,"
            + " first_published_at, revoked_reason, title, description, frontmatter";

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
     * <p><strong>Untargeted, where it used to name {@code (skill_id, digest)}.</strong> A conflict
     * target names one constraint, so naming the digest left the newer {@code (skill_id, version)}
     * one — a version name reused with different content (ADR 0033) — to surface as a duplicate-key
     * SQL error, which is a 500 for something the author fixes in their own {@code SKILL.md}. With
     * no target, both conflicts are no-ops and the caller tells them apart by looking, which is the
     * only way to tell them apart at all: {@code DO NOTHING} never says which constraint refused.
     *
     * <p>The cost, stated rather than glossed: a {@code (skill_id, number)} collision would now be
     * swallowed too, instead of raising. It is unreachable — every insert here happens under the skill
     * row's lock, which is what makes the allocation below safe in the first place — and the
     * constraint stays in the schema as the guard if that ever stops being true. What is given up is
     * the loudness, not the check.
     *
     * <p><strong>The number is allocated here, but its serialisation comes from the caller.</strong>
     * {@code MAX(number) + 1} in the statement below is safe only because the submit path takes the
     * {@code skill} row's lock first: {@link SkillRepository#findOrCreate}'s {@code ON CONFLICT … DO
     * UPDATE} locks that row before its {@code WHERE} is even evaluated, so the lock is held even
     * when the update is filtered out. Two concurrent submits of one skill therefore queue on it and
     * cannot interleave. That is an <em>unenforced</em> convention — a second writer that skips
     * {@code findOrCreate}, or a raised isolation level (the subquery would read a stale snapshot),
     * breaks it. It is also what makes the cost named above unreachable, rather than merely unlikely.
     *
     * <p><strong>Which constraint refused is not asked here.</strong> {@code DO NOTHING} cannot say,
     * and catching the violation would abort the transaction. The caller decides by looking — it
     * re-reads the digest, and an absent row means the name was the conflict ({@code
     * VersionNameTakenException}). That is why {@code version} may be null without any special
     * casing: PostgreSQL treats NULLs as distinct, so a nameless version can never collide on that
     * constraint and only the digest rule applies to it.
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
    public Optional<String> insertIfAbsent(String skillId, SkillMetadata metadata,
            String draftName, String note, String digest,
            int fileCount, long totalBytes, String source, String submittedBy, String at) {
        return jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, version, draft_name, digest,
                                           file_count, total_bytes, changelog, source,
                                           submitted_by, submitted_at, state, state_at,
                                           title, description, frontmatter)
                VALUES (:id, :skillId,
                        (SELECT COALESCE(MAX(number), 0) + 1 FROM skill_version
                         WHERE skill_id = :skillId),
                        NULL, :draftName, :digest, :fileCount, :totalBytes,
                        :note, :source, :submittedBy, :at,
                        'draft', NULL, :title, :description, :frontmatter)
                ON CONFLICT DO NOTHING
                RETURNING id
                """)
                .param("id", Ulid.generate())
                .param("skillId", skillId)
                .param("draftName", draftName)
                .param("note", note == null ? "" : note)
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
     * A draft by its row id — the {@code draft:<id>} pin.
     *
     * <p>Scoped to the skill, like every other lookup here: an id from another skill's draft must
     * not resolve just because it is a well-formed id.
     */
    public Optional<VersionRow> findByDraftId(String skillId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_version"
                        + " WHERE skill_id = :skillId AND id = :id")
                .param("skillId", skillId)
                .param("id", id)
                .query(VersionRepository::row)
                .optional();
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
            case VersionPin.Draft(String id) -> findByDraftId(skillId, id);
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
                        rs.getBoolean("is_current")))
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
                        rs.getBoolean("is_current")))
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
                rs.getString("frontmatter"));
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
            String revokedReason, String title, String description, String frontmatter) {
    }
}
