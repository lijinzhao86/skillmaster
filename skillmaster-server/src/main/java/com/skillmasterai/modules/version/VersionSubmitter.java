package com.skillmasterai.modules.version;

import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.version.internal.VersionRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Internal collaborator extracted from {@link SkillVersionService}. */
final class VersionSubmitter {

    private static final Logger LOG = LoggerFactory.getLogger(VersionSubmitter.class);

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

        // **Replay is decided before the insert, not by a unique constraint.** Drafts may share
        // content (ADR 0135), so `(skill_id, digest)` is no longer unique for them. A non-draft row
        // holding this content is the same version for everybody; a draft only counts when this
        // caller submitted it — somebody else's draft is not "you already submitted this".
        Optional<VersionRepository.VersionRow> existing =
                versions.findNonDraftBySkillAndDigest(skillId, manifest.digest())
                        .or(() -> versions.findDraftBySkillAndDigestAndSubmitter(
                                skillId, manifest.digest(), submittedBy));
        boolean inserted = existing.isEmpty();
        VersionRepository.VersionRow row;
        if (existing.isPresent()) {
            // On a replay the authoritative submitted_at and version name are the row's; the
            // caller comparing either must not see them move.
            //
            // **A permission that differs is the one thing a replay cannot absorb silently.** The
            // digest is content-only, so re-uploading the same bytes with `chmod +x` lands here —
            // and storing the old mode would answer "done" to a change that did not happen.
            reconcileModes(existing.get(), manifest, policyFor(existing.get(), source));
            row = existing.get();
        } else {
            String id = versions.insertIfAbsent(skillId, skill, draftName, note,
                    manifest.digest(), manifest.fileCount(), manifest.totalBytes(), source,
                    submittedBy, at, releaseType);
            versions.insertFiles(id, manifest);
            row = versions.findByRowId(skillId, id)
                    .orElseThrow(() -> new IllegalStateException(
                            "version " + id + " vanished after submit"));
        }

        // The version set has changed — or the same set has been asked about again — and either way
        // the card's "what is waiting" is this skill's draft side now (V21). M12 writes it because
        // it owns the row; M7 computes it because it owns the versions.
        metadata.recordSubmission(skillId, rows.draftProjectionOf(skillId));

        // A first submission is one where this call inserted the version *and* it is the only one —
        // which is exactly "this call created the skill", since a skill comes into being with its
        // first version. Asked only on the insert branch: on a replay the skill plainly already
        // existed. Counting rather than trusting an upsert's own report, because `findOrCreate`
        // cannot say whether it inserted without a trick this does not need.
        boolean skillCreated = inserted && versions.countFor(skillId) == 1;

        return new SubmitOutcome(skillId, row.id(), row.number(), row.version(), row.draftName(),
                row.note(), row.digest(), row.fileCount(), row.totalBytes(), row.submittedAt(),
                inserted, skillCreated, row.state());
    }

    /**
     * What a replay does when the stored permissions are not the incoming ones.
     *
     * <p>The file sets are identical by construction — the same digest means the same paths — so
     * every incoming entry has a counterpart, and only the mode can differ.
     *
     * <ul>
     *   <li>{@link ModeReplay#REFUSE} — a person re-uploaded the same bytes. Publishing is a
     *       deliberate act, and changing a version others already hold without saying so is the
     *       thing this project spends the most effort on; the caller is told what happened.</li>
     *   <li>{@link ModeReplay#REPAIR} — a sync's own draft. Drafts are mutable (ADR 0135), the
     *       repository is the source of truth, and refusing would leave the entry failing on every
     *       run: the transaction rolls back, the anchor never advances, and it retries forever.</li>
     *   <li>{@link ModeReplay#TOLERATE} — a sync against a row that is <em>not</em> a draft. That row
     *       is frozen: ADR 0064 §决定 1 says a published / revoked / discarded version's
     *       {@code version_file} does not change, and the mode travels in the archive, so changing
     *       it would hand two callers different bytes for one digest. The entry therefore reports
     *       unchanged and the mode waits for the next content change — which is where a new version
     *       carries it. Repairing history here would be a backfill, and a backfill is a decision
     *       with an audit trail, not a side effect of a sync run.</li>
     * </ul>
     */
    private enum ModeReplay { REFUSE, REPAIR, TOLERATE }

    private static ModeReplay policyFor(VersionRepository.VersionRow held, String source) {
        if (!"sync".equals(source)) {
            return ModeReplay.REFUSE;
        }
        return "draft".equals(held.state()) ? ModeReplay.REPAIR : ModeReplay.TOLERATE;
    }

    private void reconcileModes(VersionRepository.VersionRow held, Manifest incoming,
            ModeReplay policy) {
        Manifest current = versions.filesOf(held.id());
        for (ManifestEntry entry : incoming.entries()) {
            Optional<ManifestEntry> before = current.find(entry.relpath());
            if (before.isEmpty() || before.get().mode() == entry.mode()) {
                continue;
            }
            switch (policy) {
                case REPAIR -> {
                    versions.updateFileMode(held.id(), entry.relpath(), entry.mode());
                    LOG.info("sync repaired the mode of {} in draft {}: {} -> {}",
                            entry.relpath(), held.id(), octal(before.get().mode()),
                            octal(entry.mode()));
                }
                case TOLERATE -> LOG.warn("sync found a different mode for {} in the frozen "
                                + "version {} ({} -> {}); left alone — a new version is where a "
                                + "mode change takes effect",
                        entry.relpath(), held.id(), octal(before.get().mode()),
                        octal(entry.mode()));
                case REFUSE -> {
                    String name = held.number() == 0 ? "" : "（@%s）".formatted(held.number());
                    throw new ModeChangedOnReplayException(String.format(
                            "这次提交的内容与已有版本相同%s，但 %s 的可执行位不同（%s → %s）。"
                                    + "平台按内容判定版本，所以这个改动不会生效；请改动任意一个文件后重新提交。",
                            name, entry.relpath(), octal(before.get().mode()),
                            octal(entry.mode())));
                }
            }
        }
    }

    private static String octal(int mode) {
        return "0" + Integer.toOctalString(mode);
    }

    /**
     * Creates a draft from a base version, with or without a first edit.
     *
     * <p>Unlike {@link #submitVersion}, a digest that already exists is <strong>not</strong> a
     * replay: this request is creating a new draft row, and drafts may share content with any
     * other row (ADR 0135). The caller decides whether a first change is applied; the copy path
     * passes the base's manifest unchanged.
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
        // No digest check: drafts may share content with any row (ADR 0135). The partial unique
        // index covers only non-draft rows, and this insert is always a draft.
        // The editor's path declares no release type: v18 puts the type on submissions and on the
        // queue, and this draft is made from a baseline inside the editor (PRD §不做).
        // The content index no longer covers drafts (ADR 0135), and the per-skill lock serialises
        // the number allocation: the insert either lands or throws a duplicate key for DraftRecorder
        // to retry.
        String inserted = versions.insertIfAbsent(access.skillId(), skill, draftName,
                note, manifest.digest(), manifest.fileCount(), manifest.totalBytes(), source,
                submittedBy, at, null);
        versions.insertFiles(inserted, manifest);
        metadata.recordSubmission(access.skillId(), rows.draftProjectionOf(access.skillId()));
        VersionRepository.VersionRow row = versions.findByRowId(access.skillId(), inserted)
                .orElseThrow(() -> new IllegalStateException(
                        "version " + inserted + " vanished after create"));
        return rows.summary(access, row);
    }
}
