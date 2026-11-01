package com.skillmasterai.modules.version;

import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.version.internal.VersionRepository;
import java.util.Optional;

/** Internal collaborator extracted from {@link SkillVersionService}. */
final class VersionSubmitter {

    private final VersionRepository versions;
    private final VersionProjectionSink metadata;
    private final VersionRows rows;

    VersionSubmitter(VersionRepository versions, VersionProjectionSink metadata, VersionRows rows) {
        this.versions = versions;
        this.metadata = metadata;
        this.rows = rows;
    }

    /**
     * Records a version of a skill M12 has already found or created, as a draft.
     *
     * <p>The blobs are stored by the caller before this is called: M7 deals in {@link Manifest},
     * which carries digests and sizes, and never touches bytes. That keeps the
     * {@link com.skillmasterai.modules.blob.BlobStore} seam the only way to reach content.
     *
     * <p>Must be called inside {@link #underExclusiveWrite}.
     */
    public SubmitOutcome submit(String skillId, SkillMetadata skill, Manifest manifest,
            String draftName, String note, String submittedBy, String source, String at,
            String releaseType) {
        return record(skillId, skill, manifest, draftName, note, submittedBy, source, at,
                releaseType);
    }

    /**
     * Records a version of a skill that already exists, as a draft — the {@code write} path
     * (ADR 0034).
     *
     * <p><strong>It cannot create a skill, and that is the point of having it at all.</strong>
     * Submitting into somebody else's namespace is not expressible through {@link #submit}: the
     * target there is resolved by the use case from the caller's own namespace and never from the
     * request, which is what keeps a submission from being a cross-namespace write primitive. Here
     * the target <em>is</em> named, so the one thing that makes that safe is that it can only ever
     * add a version to a skill the caller has already been given write access to — never bring one
     * into being. Creatable names stay confined to namespaces the caller owns.
     *
     * <p>Must be called inside {@link #underExclusiveWrite}.
     *
     * @return what was recorded
     * @throws NotPermittedException when the caller may read it and may not write it
     */
    public Optional<SubmitOutcome> submitVersion(Access access, SkillMetadata skill,
            Manifest manifest, String draftName, String note, String submittedBy, String source,
            String at, String releaseType) {
        if (!access.atLeast(GrantRole.WRITE)) {
            throw new NotPermittedException("'" + access.name() + "' is shared with you below the "
                    + "`write` rung; submitting versions needs `write`");
        }
        return Optional.of(record(access.skillId(), skill, manifest, draftName, note,
                submittedBy, source, at, releaseType));
    }

    /**
     * The half of a submission both entry points share: insert or recognise, write the manifest,
     * read back, and rewrite the draft projection.
     *
     * <p>Extracted when {@link #submitVersion} arrived, and the extraction is the honest one — the
     * two differ in how the skill is found and in nothing else.
     */
    private SubmitOutcome record(String skillId, SkillMetadata skill, Manifest manifest,
            String draftName, String note, String submittedBy, String source, String at,
            String releaseType) {
        // The same per-skill critical section the file write takes. Without it a submission can
        // commit a digest between a write's check and its UPDATE, and that write would see a 500
        // where the contract says 409.
        //
        // **The absence of a deadlock comes from the table lock, not from a shared order.** A
        // submission holds `version_file`'s SHARE ROW EXCLUSIVE and a file write holds its ROW
        // EXCLUSIVE; the two are mutually exclusive, so a submission with the skill row and a write
        // with this advisory lock are never both in flight. That argument depends on every writer
        // taking the table lock first — do not add a write path that skips `version_file`, and do
        // not let submission stop taking the table lock.
        versions.lockContentOf(skillId);
        Optional<String> inserted =
                versions.insertIfAbsent(skillId, skill, draftName, note, manifest.digest(),
                        manifest.fileCount(), manifest.totalBytes(), source, submittedBy, at,
                        releaseType);

        if (inserted.isPresent()) {
            versions.insertFiles(inserted.get(), manifest);
        }
        // Nothing else to tell apart: the version name is no longer written at submission, so the
        // only unique constraint this insert can meet is the digest's, and DO NOTHING on a replay
        // is the answer rather than an error. A name collision now happens at publish, where the
        // number is chosen.

        // Read back rather than trusting the inputs: on a replay the authoritative submitted_at and
        // version name are the row's, so a client comparing either would otherwise see them move.
        VersionRepository.VersionRow row =
                versions.findBySkillAndDigest(skillId, manifest.digest(), false)
                        .orElseThrow(() -> new IllegalStateException(
                                "version for digest " + manifest.digest() + " vanished mid-submit"));

        // The version set has changed — or the same set has been asked about again — and either way
        // the card's "what is waiting" is this skill's draft side now (V21). M12 writes it because
        // it owns the row; M7 computes it because it owns the versions.
        metadata.recordSubmission(skillId, rows.draftProjectionOf(skillId));

        // A first submission is one where this call inserted the version *and* it is the only one —
        // which is exactly "this call created the skill", since a skill comes into being with its
        // first version. Asked only on the insert branch: on a replay the skill plainly already
        // existed. Counting rather than trusting an upsert's own report, because `findOrCreate`
        // cannot say whether it inserted without a trick this does not need.
        boolean skillCreated = inserted.isPresent() && versions.countFor(skillId) == 1;

        return new SubmitOutcome(skillId, row.id(), row.number(), row.version(), row.draftName(),
                row.note(), row.digest(), row.fileCount(), row.totalBytes(), row.submittedAt(),
                inserted.isPresent(), skillCreated, row.state());
    }

    /**
     * Creates a draft from a base version plus one edit, and refuses content another version
     * already holds (v8 TD §底稿的读取与复制).
     *
     * <p>Unlike {@link #submitVersion}, a digest that already exists is <strong>not</strong> a
     * replay: this request is creating a row, and the row it collided with is the answer. So it
     * throws {@code content_already_exists} rather than returning the existing version.
     *
     * <p>Must be called inside {@link #underExclusiveWrite}; the caller also gives the id a chance
     * to be redrawn by going through {@code DraftRecorder}.
     */
    public VersionSummary submitFromBase(Access access, SkillMetadata skill, Manifest manifest,
            String draftName, String note, String submittedBy, String source, String at) {
        if (!access.atLeast(GrantRole.WRITE)) {
            throw new NotPermittedException("'" + access.name() + "' is shared with you below the "
                    + "`write` rung; creating a version needs `write`");
        }
        versions.lockContentOf(access.skillId());
        Optional<VersionRepository.VersionRow> existing =
                versions.findBySkillAndDigest(access.skillId(), manifest.digest(), false);
        if (existing.isPresent()) {
            throw rows.contentConflict(access, existing.get());
        }
        // The editor's path declares no release type: v18 puts the type on submissions and on the
        // queue, and this draft is made from a baseline inside the editor (PRD §不做).
        Optional<String> inserted = versions.insertIfAbsent(access.skillId(), skill, draftName,
                note, manifest.digest(), manifest.fileCount(), manifest.totalBytes(), source,
                submittedBy, at, null);
        if (inserted.isEmpty()) {
            // The unique constraint is the second line of defence: another transaction inserted the
            // same digest between the check above and this insert. Answer with that row, not a 500.
            VersionRepository.VersionRow raced = versions
                    .findBySkillAndDigest(access.skillId(), manifest.digest(), false)
                    .orElseThrow(() -> new IllegalStateException(
                            "content " + manifest.digest() + " vanished mid-create"));
            throw rows.contentConflict(access, raced);
        }
        versions.insertFiles(inserted.get(), manifest);
        metadata.recordSubmission(access.skillId(), rows.draftProjectionOf(access.skillId()));
        VersionRepository.VersionRow row = versions.findByRowId(access.skillId(), inserted.get())
                .orElseThrow(() -> new IllegalStateException(
                        "version " + inserted.get() + " vanished after create"));
        return rows.summary(access, row);
    }
}
