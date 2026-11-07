package com.skillmasterai.usecase.sync;

import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillLicense;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SkillSource;
import com.skillmasterai.modules.skill.SourceAdapter.SourceBinding;
import com.skillmasterai.modules.skill.SourceService;
import com.skillmasterai.modules.version.ExecutableMode;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.DraftRecorder;
import com.skillmasterai.usecase.SubmitSkillUseCase;
import com.skillmasterai.usecase.model.SubmittedSkill;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only part of the sync that touches the domain (ADR 0157).
 *
 * <p>It does not have its own "create skill → create version → store files" path: it builds an
 * in-memory zip and calls the same submission a person's upload calls. Digest, normalisation,
 * upload limits, manifest, version state machine and audit trail are therefore identical, and there
 * is no second state machine to keep in step.
 *
 * <p><strong>The origin is checked twice.</strong> Once before submission (the pipeline's
 * preflight) and once inside the write transaction, because the row can change between the two.
 * Submission and the source facts commit together: a draft with no origin is not a state this
 * leaves behind.
 */
public final class SyncDraftWriter {

    private static final Logger LOG = LoggerFactory.getLogger(SyncDraftWriter.class);

    private static final int NOTE_LIMIT = 200;

    private final SubmitSkillUseCase submit;
    private final DraftRecorder drafts;
    private final SkillMetadataService metadata;
    private final SkillVersionService versions;
    private final SourceService sources;

    public SyncDraftWriter(SubmitSkillUseCase submit, DraftRecorder drafts,
            SkillMetadataService metadata, SkillVersionService versions, SourceService sources) {
        this.submit = submit;
        this.drafts = drafts;
        this.metadata = metadata;
        this.versions = versions;
        this.sources = sources;
    }

    public WriteResult write(PreparedEntry prepared, Namespace official) {
        String name = prepared.platformName();
        SourceBinding binding = prepared.binding();
        Optional<SkillSource> held = sources.findByAddress(official.id(), name);
        if (held.isPresent() && !sameOrigin(held.get(), binding)) {
            return WriteResult.blocked("origin_conflict");
        }

        byte[] zip = zip(prepared);
        String note = note(prepared);
        String draftName = draftName(prepared.revision());
        AuthenticatedSubject subject = AuthenticatedSubject.ofService(official.ownerUserId());

        Written written;
        try {
            written = drafts.record(() -> {
                // The preflight and the write are not one transaction; the row can appear between
                // them. Re-reading here is what makes the conflict impossible rather than unlikely.
                Optional<SkillSource> now = sources.findByAddress(official.id(), name);
                if (now.isPresent() && !sameOrigin(now.get(), binding)) {
                    throw new OriginConflict();
                }
                SubmittedSkill result = submit.submit(zip, draftName, note, null, subject, "sync");
                /* A replay can return a draft the official account uploaded by hand. Its source
                   still says `zip` and is left alone: the publish path keeps the sync's origin
                   because this transaction writes a snapshot for the version, not because of the
                   row's source — and leaving `zip` keeps that draft out of the next run's
                   old-sync-draft cleanup. */
                SkillLicense license = licenseOf(prepared.license(), result.draftId());
                SourceService.SyncRecord record = sources.recordSynced(result.skillId(),
                        result.draftId(), binding, prepared.revision(), prepared.signalsJson(),
                        license);
                if (record == SourceService.SyncRecord.ORIGIN_CONFLICT) {
                    throw new OriginConflict();
                }
                /* The snapshot's state is the fact the result line needs: a replay that lands on a
                   hand-uploaded draft still writes one, and it may be the pending one. */
                return new Written(result,
                        record == SourceService.SyncRecord.RECORDED_PENDING);
            });
        } catch (OriginConflict conflict) {
            return WriteResult.blocked("origin_conflict");
        } catch (com.skillmasterai.modules.version.SkillDeletedException deleted) {
            return WriteResult.blocked("skill_deleted");
        }

        SubmittedSkill submitted = written.submitted();
        discardOlderDrafts(submitted, official);
        return submitted.created()
                ? WriteResult.created(submitted.skillId(), submitted.draftId(),
                        written.signalsPending())
                : WriteResult.replayed(submitted.skillId(), submitted.draftId(),
                        written.signalsPending());
    }

    /** What the write transaction produced, with the fact the result line reports on. */
    private record Written(SubmittedSkill submitted, boolean signalsPending) {
    }

    /**
     * Drops earlier unpublished sync drafts for the same skill.
     *
     * <p>After the new draft commits, deliberately: a cleanup that fails must not lose the content
     * it was cleaning up for. Only rows this same service identity submitted with
     * {@code source='sync'} are candidates, so a person's own draft is never touched.
     */
    private void discardOlderDrafts(SubmittedSkill submitted, Namespace official) {
        if (!"draft".equals(submitted.state())) {
            return;
        }
        try {
            /* M7's discard writes the version row and the skill row's draft projection, so it has
               to share one transaction. This is a second, separate transaction from the write
               above on purpose: cleanup failing must not undo the draft it was cleaning up for. */
            List<String> discarded = drafts.record(() -> {
                Optional<Access> access = metadata.accessFor(official.id(), submitted.name(),
                        new Caller(official.ownerUserId(), official.id()));
                if (access.isEmpty()) {
                    return List.<String>of();
                }
                return versions.discardSyncDrafts(access.get(), submitted.draftId(),
                        official.ownerUserId());
            });
            if (!discarded.isEmpty()) {
                LOG.info("sync discarded old drafts skill={} versions={}", submitted.name(),
                        String.join(",", discarded));
            }
        } catch (RuntimeException failure) {
            LOG.warn("sync could not discard older drafts of {}; the next run retries",
                    submitted.name(), failure);
        }
    }

    private static boolean sameOrigin(SkillSource source, SourceBinding binding) {
        return source.kind().equals(binding.kind())
                && source.locator().equals(binding.locator())
                && source.path().equals(binding.path());
    }

    private static SkillLicense licenseOf(LicenseDecision decision, String versionId) {
        return new SkillLicense(versionId,
                decision.spdx() == null ? "NOASSERTION" : decision.spdx(),
                decision.name() == null ? "未声明" : decision.name(),
                decision.copyright(),
                decision.status(),
                decision.text(),
                decision.path(),
                decision.url(),
                decision.detail() == null ? "{}" : decision.detail(),
                LocalDate.now(ZoneOffset.UTC).toString());
    }

    private static String draftName(String revision) {
        String shortSha = revision == null || revision.isBlank() ? "unknown" : shortSha(revision);
        return "sync-" + shortSha;
    }

    private static String shortSha(String revision) {
        return revision.length() > 8 ? revision.substring(0, 8) : revision;
    }

    private static String note(PreparedEntry prepared) {
        LicenseDecision license = prepared.license();
        /* A missing license's conclusion *is* its note. Writing both produced "未声明（未声明）",
           and the duplicate is the kind of thing an operator reads as a bug in the sync. */
        String conclusion = license.status()
                == com.skillmasterai.modules.skill.LicenseStatus.MISSING
                        ? ""
                        : license.spdx();
        if (license.note() != null && !license.note().isBlank()) {
            conclusion = conclusion + license.note();
        }
        String source = prepared.binding().kind() + " " + prepared.binding().locator() + "@"
                + shortSha(prepared.revision());
        String note = "同步 · " + source + " · " + conclusion;
        String rename = prepared.platformName().equals(prepared.document().name())
                ? ""
                : " · " + prepared.document().name() + " → " + prepared.platformName();
        if (!rename.isEmpty() && note.length() + rename.length() <= NOTE_LIMIT) {
            note = note + rename;
        }
        if (note.length() > NOTE_LIMIT) {
            note = "同步 · @" + shortSha(prepared.revision()) + " · " + conclusion;
        }
        /* Last resort only: cut the source, never the conclusion. If even the conclusion alone is
           too long, the conclusion is what survives — the operator decides on it. */
        if (note.length() > NOTE_LIMIT) {
            note = conclusion.length() > NOTE_LIMIT
                    ? conclusion.substring(0, NOTE_LIMIT)
                    : "同步 · " + conclusion;
        }
        return note.length() > NOTE_LIMIT ? note.substring(0, NOTE_LIMIT) : note;
    }

    /**
     * The files as a zip whose single root is the platform name.
     *
     * <p>The validator compares the stripped directory name with {@code SKILL.md}'s name, so the
     * root is not cosmetic — a zip rooted at something else would be refused. When the platform
     * name differs from upstream (ADR 0160), the root file's {@code name} is rewritten to match;
     * every other byte is copied as fetched.
     */
    private static byte[] zip(PreparedEntry prepared) {
        String root = prepared.platformName();
        String dir = prepared.entry().path();
        List<FetchedFile> files = prepared.files().stream()
                .sorted(Comparator.comparing(FetchedFile::relpath))
                .toList();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes)) {
            for (FetchedFile file : files) {
                String relpath = strip(dir, file.relpath());
                if (relpath == null) {
                    continue;
                }
                byte[] content = file.content();
                if (relpath.equals("SKILL.md") && !root.equals(prepared.document().name())) {
                    content = SkillNameTransform.rewriteSkillMd(content, root);
                }
                /* The mode in this archive is the one the version will store, so the intermediate
                   package says the same thing the platform will (v51, ADR 0166). It is derived from
                   the path, not from the tar: the same relpaths always mean the same modes. */
                ZipArchiveEntry entry = new ZipArchiveEntry(root + "/" + relpath);
                entry.setUnixMode(ExecutableMode.of(relpath));
                entry.setTime(0);
                zip.putArchiveEntry(entry);
                zip.write(content);
                zip.closeArchiveEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("building the sync zip failed", failure);
        }
    }

    private static String strip(String dir, String path) {
        if (dir == null || dir.isEmpty()) {
            return path;
        }
        return path.startsWith(dir + "/") ? path.substring(dir.length() + 1) : null;
    }

    private static final class OriginConflict extends RuntimeException {
        private OriginConflict() {
            super(null, null, false, false);
        }
    }
}
