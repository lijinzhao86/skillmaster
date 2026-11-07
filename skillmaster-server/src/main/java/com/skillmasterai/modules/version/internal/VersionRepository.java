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

    private final JdbcClient jdbc;
    private final VersionRowRepository rows;
    private final VersionFileRepository files;
    private final VersionQueryRepository queries;

    /**
     * @param ids the source of a new row's id. A seam rather than a static call so a test can
     *            inject a colliding id and prove {@code DraftRecorder} retries; production passes
     *            {@link com.skillmasterai.common.ShortCode#generate}
     */
    public VersionRepository(JdbcClient jdbc, Supplier<String> ids) {
        this.jdbc = jdbc;
        this.rows = new VersionRowRepository(jdbc, ids);
        this.files = new VersionFileRepository(jdbc);
        this.queries = new VersionQueryRepository(jdbc, rows);
    }

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

    /**
     * The newest version of a skill by number, whatever its state.
     *
     * <p>v50 asks this to pair M12's snapshots with the newest version without M12 reading
     * {@code skill_version}. Discarded and revoked rows are included on purpose: "seen" is about
     * what the sync has ever produced, not about what is still a draft.
     */
    public Optional<String> newestVersionId(String skillId) {
        return jdbc.sql("SELECT id FROM skill_version WHERE skill_id = :skill"
                        + " ORDER BY number DESC LIMIT 1")
                .param("skill", skillId)
                .query((rs, rowNum) -> rs.getString(1))
                .optional();
    }

    /**
     * The newest version among {@code ids}, whatever its state.
     *
     * <p>v50 asks this for the versions that actually carry a source snapshot: a hand-made draft
     * newer than the last synced version has no snapshot, and it must not hide the last revision
     * the sync has seen.
     */
    public Optional<String> newestVersionIdAmong(String skillId, java.util.Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT id FROM skill_version WHERE skill_id = :skill"
                        + " AND id = ANY(:ids) ORDER BY number DESC LIMIT 1")
                .param("skill", skillId)
                .param("ids", ids.toArray(String[]::new))
                .query((rs, rowNum) -> rs.getString(1))
                .optional();
    }

    /** @see VersionRowRepository#insertIfAbsent */
    public String insertIfAbsent(String skillId, SkillMetadata metadata,
            String draftName, String note, String digest,
            int fileCount, long totalBytes, String source, String submittedBy, String at,
            String releaseType) {
        return rows.insertIfAbsent(skillId, metadata, draftName, note, digest, fileCount,
                totalBytes, source, submittedBy, at, releaseType);
    }

    /** @see VersionFileRepository#insertFiles */
    public void insertFiles(String versionId, Manifest manifest) {
        files.insertFiles(versionId, manifest);
    }

    /** @see VersionRowRepository#findBySkillAndDigest */
    public Optional<VersionRow> findBySkillAndDigest(String skillId, String digest,
            boolean liveOnly) {
        return rows.findBySkillAndDigest(skillId, digest, liveOnly);
    }

    /** @see VersionRowRepository#findNonDraftBySkillAndDigest */
    public Optional<VersionRow> findNonDraftBySkillAndDigest(String skillId, String digest) {
        return rows.findNonDraftBySkillAndDigest(skillId, digest);
    }

    /** @see VersionRowRepository#findDraftBySkillAndDigestAndSubmitter */
    public Optional<VersionRow> findDraftBySkillAndDigestAndSubmitter(String skillId, String digest,
            String submittedBy) {
        return rows.findDraftBySkillAndDigestAndSubmitter(skillId, digest, submittedBy);
    }

    /** @see VersionRowRepository#findBySkillAndVersion */
    public Optional<VersionRow> findBySkillAndVersion(String skillId, String version,
            boolean liveOnly) {
        return rows.findBySkillAndVersion(skillId, version, liveOnly);
    }

    /** @see VersionRowRepository#countFor */
    public int countFor(String skillId) {
        return rows.countFor(skillId);
    }

    /** @see VersionRowRepository#findCurrent */
    public Optional<VersionRow> findCurrent(String versionId) {
        return rows.findCurrent(versionId);
    }

    /** @see VersionRowRepository#findNewestNotDiscarded */
    public Optional<VersionRow> findNewestNotDiscarded(String skillId, boolean includeRevoked) {
        return rows.findNewestNotDiscarded(skillId, includeRevoked);
    }

    /** @see VersionRowRepository#findByRowId */
    public Optional<VersionRow> findByRowId(String skillId, String id) {
        return rows.findByRowId(skillId, id);
    }

    /** @see VersionRowRepository#findByRowIdForUpdate */
    public Optional<VersionRow> findByRowIdForUpdate(String skillId, String id) {
        return rows.findByRowIdForUpdate(skillId, id);
    }

    /** @see VersionFileRepository#putFile */
    public void putFile(String versionId, ManifestEntry entry) {
        files.putFile(versionId, entry);
    }

    /** @see VersionFileRepository#deleteFile */
    public int deleteFile(String versionId, String relpath) {
        return files.deleteFile(versionId, relpath);
    }

    /** @see VersionFileRepository#updateFileMode */
    public int updateFileMode(String versionId, String relpath, int mode) {
        return files.updateFileMode(versionId, relpath, mode);
    }

    /** @see VersionFileRepository#renameFile */
    public int renameFile(String versionId, String from, String to) {
        return files.renameFile(versionId, from, to);
    }

    /** @see VersionFileRepository#copyFile */
    public void copyFile(String versionId, ManifestEntry source, String to) {
        files.copyFile(versionId, source, to);
    }

    /** @see VersionRowRepository#updateContent */
    public int updateContent(String versionId, String digest, int fileCount, long totalBytes,
            SkillMetadata metadata) {
        return rows.updateContent(versionId, digest, fileCount, totalBytes, metadata);
    }

    /** @see VersionQueryRepository#findVisible */
    public Optional<VersionRow> findVisible(String skillId, VersionPin pin,
            java.util.Set<String> states) {
        return queries.findVisible(skillId, pin, states);
    }

    /** @see VersionRowRepository#findByPinIgnoringState */
    public Optional<VersionRow> findByPinIgnoringState(String skillId, VersionPin pin) {
        return rows.findByPinIgnoringState(skillId, pin);
    }

    /** @see VersionRowRepository#findNewest */
    public Optional<VersionRow> findNewest(String skillId) {
        return rows.findNewest(skillId);
    }

    /** @see VersionRowRepository#markPublished */
    public int markPublished(String versionId, String releaseName, String at) {
        return rows.markPublished(versionId, releaseName, at);
    }

    /** @see VersionRowRepository#markRevoked */
    public int markRevoked(String versionId, String at, String reason) {
        return rows.markRevoked(versionId, at, reason);
    }

    /** @see VersionRowRepository#markRestored */
    public int markRestored(String versionId, String at) {
        return rows.markRestored(versionId, at);
    }

    /** @see VersionRowRepository#markRolledBack */
    public int markRolledBack(String targetId, String displacedId, String at) {
        return rows.markRolledBack(targetId, displacedId, at);
    }

    /** @see VersionRowRepository#updateDraftName */
    public int updateDraftName(String versionId, String name) {
        return rows.updateDraftName(versionId, name);
    }

    /** @see VersionRowRepository#updateDraftNote */
    public int updateDraftNote(String versionId, String note) {
        return rows.updateDraftNote(versionId, note);
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

    /** @see VersionQueryRepository#draftsOf */
    public List<DraftRow> draftsOf(Collection<String> skillIds) {
        return queries.draftsOf(skillIds);
    }

    /** @see VersionQueryRepository#draftsBySource */
    public List<String> draftsBySource(String skillId, String source, String submittedBy,
            String exceptId) {
        return queries.draftsBySource(skillId, source, submittedBy, exceptId);
    }

    /** @see VersionRowRepository#updateReleaseType */
    public int updateReleaseType(String versionId, String releaseType) {
        return rows.updateReleaseType(versionId, releaseType);
    }

    /** @see VersionRowRepository#markDiscarded */
    public int markDiscarded(String versionId, String at) {
        return rows.markDiscarded(versionId, at);
    }

    /** @see VersionQueryRepository#versionsOf */
    public List<VersionSummary> versionsOf(String skillId, String currentVersionId) {
        return queries.versionsOf(skillId, currentVersionId);
    }

    /** @see VersionQueryRepository#versionsVisible */
    public List<VersionSummary> versionsVisible(String skillId, String currentVersionId,
            java.util.Set<String> states) {
        return queries.versionsVisible(skillId, currentVersionId, states);
    }

    /** @see VersionQueryRepository#publishedVersionsOf */
    public List<VersionSummary> publishedVersionsOf(String skillId, String currentVersionId) {
        return queries.publishedVersionsOf(skillId, currentVersionId);
    }

    /** @see VersionQueryRepository#draftProjectionOf */
    public Optional<DraftProjection> draftProjectionOf(String skillId) {
        return queries.draftProjectionOf(skillId);
    }

    /** @see VersionFileRepository#filesOf */
    public Manifest filesOf(String versionId) {
        return files.filesOf(versionId);
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
