package com.skillmasterai.usecase;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.distribution.ArchiveArtifact;
import com.skillmasterai.modules.distribution.SkillDistributionService;
import com.skillmasterai.modules.ingest.ReservedPaths;
import com.skillmasterai.modules.ingest.SkillSidecar;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionState;
import com.skillmasterai.modules.version.VersionStateException;
import com.skillmasterai.modules.version.VersionSummary;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One version as a zip, for either plane (ADR 0068).
 *
 * <p><strong>One manifest, read once.</strong> The version is resolved through
 * {@link ReadAuthorVersionUseCase#show}, which already applies the author plane's rung and its
 * state visibility; then the manifest is read <em>once</em> and the archive is built from it. Blobs
 * are immutable, so every byte that follows belongs to that manifest even if a draft is edited
 * meanwhile. The transaction is {@code REPEATABLE READ} so the row and its files are one snapshot.
 *
 * <p><strong>Calling another use case is new here and purposeful.</strong> {@code show} owns the
 * visibility rule; duplicating it would be a second statement of "who may read which state". Its
 * annotation is {@code readOnly = true} with default propagation, so it <em>joins</em> this
 * transaction — the isolation below is the one that applies. Do not make it {@code REQUIRES_NEW}.
 *
 * <p><strong>Discarded is refused even for {@code maintain}.</strong> The product shows no files
 * for that state, so there is nothing to package; the pin resolves, and the refusal is 400
 * {@code invalid_version_state} — the same answer the existing state machine gives.
 *
 * <p>What the snapshot protects is rows, not bytes already reclaimed: a concurrent edit that
 * deletes a file from this draft can let the GC sweep that blob before it is opened, and the
 * request then fails (500) before any byte is sent. That window is the existing single-file read's
 * as well; holding the GC lock for a download would stop every write (v9 TD §架构).
 */
@Component
public class ArchiveSkillVersionUseCase {

    private final ReadAuthorVersionUseCase reader;
    private final SkillVersionService versions;
    private final SkillDistributionService distribution;

    public ArchiveSkillVersionUseCase(ReadAuthorVersionUseCase reader,
            SkillVersionService versions, SkillDistributionService distribution) {
        this.reader = reader;
        this.versions = versions;
        this.distribution = distribution;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<ArchiveArtifact> archive(String namespaceSlug, String name, VersionPin pin,
            AuthenticatedSubject subject) {
        Optional<ReadAuthorVersionUseCase.View> shown = reader.show(namespaceSlug, name, pin, subject);
        if (shown.isEmpty()) {
            return Optional.empty();
        }
        ReadAuthorVersionUseCase.View view = shown.get();
        VersionSummary row = view.version();
        if (VersionState.DISCARDED.equals(row.state())) {
            throw new VersionStateException("this version has been discarded; its files are not"
                    + " downloadable", ErrorCode.INVALID_VERSION_STATE);
        }

        Manifest manifest = versions.filesOf(row);
        // A file already at the reserved path is content of that old version; rendering a sidecar
        // beside it would put two entries with one name in the zip (ADR 0069).
        byte[] sidecar = manifest.find(ReservedPaths.SIDECAR).isPresent()
                ? null : SkillSidecar.render(row.draftName(), row.note());

        String suffix = row.version() != null ? row.version() : row.id();
        return Optional.of(distribution.archive(manifest, sidecar,
                view.access().name() + "@" + suffix + ".zip"));
    }
}
