package com.skillmasterai.modules.version;

import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.version.internal.BlobGc;
import com.skillmasterai.modules.version.internal.VersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * M7: the skill's immutable versions, the bytes they reference, and the draft / publish lifecycle.
 *
 * <p><strong>Submitting and publishing are separate operations here</strong> (ADR 0031). Submitting
 * records a version and stops; nothing the consumption plane can see has changed. Publishing marks
 * a version live and — since ADR 0053 — hands the facts to M12, which owns the skill row and moves
 * the pointer.
 *
 * <p><strong>This class never opens a transaction.</strong> §2.5 rule 2 puts cross-module
 * transaction boundaries in the use-case layer, and both operations are nothing but cross-module —
 * so every method here is written to be called inside a transaction the caller owns. Running one
 * outside a transaction would still work, and would be wrong: the version row, its files and the
 * projection would each commit separately.
 *
 * <p><strong>It does not read {@code skill} or {@code skill_grant}, and it does not decide who may
 * do what from them.</strong> The caller asks M12 for an {@link Access} once and passes it in; this
 * module evaluates the rung it carries. That is ADR 0053's rule 2, and it is what keeps "who may
 * see this" in one statement.
 */
public final class SkillVersionService {

    private final VersionRepository versions;
    private final BlobGc blobGc;
    private final VersionProjectionSink metadata;
    private final VersionDraftEditor draftEditor;
    private final VersionSubmitter submitter;
    private final VersionPublisher publisher;
    private final VersionLifecycleService lifecycle;
    private final VersionReader reader;

    public SkillVersionService(VersionRepository versions, BlobGc blobGc,
            VersionProjectionSink metadata) {
        this.versions = versions;
        this.blobGc = blobGc;
        this.metadata = metadata;
        VersionRows rows = new VersionRows(versions, metadata);
        this.draftEditor = new VersionDraftEditor(versions, rows);
        this.submitter = new VersionSubmitter(versions, metadata, rows);
        this.publisher = new VersionPublisher(versions, metadata, rows);
        this.lifecycle = new VersionLifecycleService(versions, metadata, rows);
        this.reader = new VersionReader(versions, rows);
    }

    public <T> T underExclusiveWrite(Supplier<T> change) {
        blobGc.beginExclusiveWrite();
        T result = change.get();
        blobGc.sweep();
        return result;
    }

    public Optional<VersionSummary> openDraft(Access access, String rowId) {
        return draftEditor.openDraft(access, rowId);
    }

    public Optional<VersionSummary> openForSearch(Access access, VersionPin pin) {
        return reader.openForSearch(access, pin);
    }

    public VersionSummary lockDraft(Access access, String rowId) {
        return draftEditor.lockDraft(access, rowId);
    }

    public Manifest filesOf(VersionSummary row) {
        return draftEditor.filesOf(row);
    }

    public void putFile(String versionId, ManifestEntry entry) {
        draftEditor.putFile(versionId, entry);
    }

    public int deleteFile(String versionId, String relpath) {
        return draftEditor.deleteFile(versionId, relpath);
    }

    public int renameFile(String versionId, String from, String to) {
        return draftEditor.renameFile(versionId, from, to);
    }

    public void copyFile(String versionId, ManifestEntry source, String to) {
        draftEditor.copyFile(versionId, source, to);
    }

    public VersionSummary commitContent(Access access, VersionSummary locked, Manifest next,
            SkillMetadata metadata) {
        return draftEditor.commitContent(access, locked, next, metadata);
    }

    public SubmitOutcome submit(String skillId, SkillMetadata skill, Manifest manifest,
            String draftName, String note, String submittedBy, String source, String at,
            String releaseType) {
        return submitter.submit(skillId, skill, manifest, draftName, note, submittedBy, source, at, releaseType);
    }

    public Optional<SubmitOutcome> submitVersion(Access access, SkillMetadata skill,
            Manifest manifest, String draftName, String note, String submittedBy, String source,
            String at, String releaseType) {
        return submitter.submitVersion(access, skill, manifest, draftName, note, submittedBy, source, at, releaseType);
    }

    public VersionSummary submitFromBase(Access access, SkillMetadata skill, Manifest manifest,
            String draftName, String note, String submittedBy, String source, String at) {
        return submitter.submitFromBase(access, skill, manifest, draftName, note, submittedBy, source, at);
    }

    public List<PublishDraft> draftsOf(java.util.Collection<String> skillIds) {
        return lifecycle.draftsOf(skillIds);
    }

    public Optional<PromotionOutcome> publishVersion(Access access, VersionPin pin,
            String releaseName) {
        return publisher.publishVersion(access, pin, releaseName);
    }

    public Optional<String> discardVersion(Access access, VersionPin pin) {
        return lifecycle.discardVersion(access, pin);
    }

    public Optional<String> updateDraftName(Access access, VersionPin pin, String name) {
        return draftEditor.updateDraftName(access, pin, name);
    }

    public Optional<String> updateDraftNote(Access access, VersionPin pin, String note) {
        return draftEditor.updateDraftNote(access, pin, note);
    }

    public Optional<String> updateDraftReleaseType(Access access, VersionPin pin,
            String releaseType) {
        return draftEditor.updateDraftReleaseType(access, pin, releaseType);
    }

    public Optional<String> revokeVersion(Access access, VersionPin pin, String reason) {
        return lifecycle.revokeVersion(access, pin, reason);
    }

    public Optional<String> restoreVersion(Access access, VersionPin pin) {
        return lifecycle.restoreVersion(access, pin);
    }

    public Optional<VersionSummary> authorVersionIgnoringState(Access access, VersionPin pin) {
        return reader.authorVersionIgnoringState(access, pin);
    }

    public List<VersionSummary> authorVersions(Access access) {
        return reader.authorVersions(access);
    }

    public Optional<SkillSnapshot> liveSnapshot(Access access, VersionPin pin) {
        return reader.liveSnapshot(access, pin);
    }

    public Optional<SkillSnapshot> detailedSnapshot(Access access, VersionPin pin) {
        return reader.detailedSnapshot(access, pin);
    }

    public Optional<SkillSnapshot> authorSnapshot(Access access, VersionPin pin) {
        return reader.authorSnapshot(access, pin);
    }

    public Optional<AuthoredSkillView> authoredView(Access access, VersionPin pin) {
        return reader.authoredView(access, pin);
    }

    public AuthoredSkillView blankAuthoredView(Access access) {
        return reader.blankAuthoredView(access);
    }

    public Optional<SkillSnapshot> resolveForConsumption(Access access, VersionPin pin) {
        return reader.resolveForConsumption(access, pin);
    }

    public List<VersionSummary> versionsOf(Access access) {
        return reader.versionsOf(access);
    }

    public Optional<List<VersionSummary>> liveVersionsOf(Access access) {
        return reader.liveVersionsOf(access);
    }
}
