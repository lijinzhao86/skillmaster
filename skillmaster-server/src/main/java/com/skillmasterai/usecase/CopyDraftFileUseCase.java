package com.skillmasterai.usecase;

import com.skillmasterai.common.ConflictException;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.VersionFileNotFoundException;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.model.DraftWriteResult;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code skill cp}: adds a second path pointing at the same content (v7 做 #9).
 *
 * <p><strong>Two manifest rows, one blob.</strong> The copy shares its source's {@code blob_sha256}
 * — content addressing is what makes that safe, since the bytes can never be changed in place — and
 * the new row carries the source's {@code is_binary} rather than detecting again: the bytes did not
 * change, so the answer cannot either.
 */
@Component
public class CopyDraftFileUseCase extends DraftFileWrite {

    public CopyDraftFileUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, BlobStore blobs, AuditLog audit,
            ObjectMapper objectMapper) {
        super(namespaces, versions, metadata, blobs, audit, objectMapper);
    }

    @Transactional
    public Optional<DraftWriteResult> copy(String namespaceSlug, String name, String rowId,
            String from, String to, String ifMatch, AuthenticatedSubject subject) {
        Optional<Access> resolved = accessOf(namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        requirePath(from);
        requireTargetPath(to);
        Optional<VersionSummary> opened = versions.openDraft(access, rowId);
        if (opened.isEmpty()) {
            return Optional.empty();
        }

        String baseline = requireIfMatch(ifMatch);
        VersionSummary locked = versions.lockDraft(access, rowId);
        requireBaseline(baseline, locked);

        Manifest current = versions.filesOf(locked);
        ManifestEntry source = current.find(from)
                .orElseThrow(() -> new VersionFileNotFoundException(from));
        if (current.find(to).isPresent()) {
            throw new ConflictException(ErrorCode.FILE_ALREADY_EXISTS,
                    "this version already has a file at '" + to + "'", "to", to);
        }

        SkillMetadata declaration = to.equals(BODY_RELPATH)
                ? documentOf(blobs.get(source.blobSha256()), access.name())
                : null;

        versions.copyFile(locked.id(), source, to);
        Manifest next = versions.filesOf(locked);
        requireWithinLimits(next);
        VersionSummary after = versions.commitContent(access, locked, next, declaration);
        record(subject, access, locked, after, "copy", null, from, to);
        return result(access, after);
    }
}
