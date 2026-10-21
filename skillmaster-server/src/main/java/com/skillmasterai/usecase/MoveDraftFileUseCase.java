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
 * {@code skill mv}: renames one path inside a draft (v7 做 #9).
 *
 * <p><strong>No bytes move.</strong> The manifest row's {@code relpath} changes and its
 * {@code blob_sha256} does not; content addressing makes a move a single column's value changing.
 * That is also why the move cannot be implemented as copy-then-delete: the two rows would briefly
 * double the file count and could cross the ceiling for a skill that is only being reorganised.
 */
@Component
public class MoveDraftFileUseCase extends DraftFileWrite {

    public MoveDraftFileUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, BlobStore blobs, AuditLog audit,
            ObjectMapper objectMapper) {
        super(namespaces, versions, metadata, blobs, audit, objectMapper);
    }

    @Transactional
    public Optional<DraftWriteResult> move(String namespaceSlug, String name, String rowId,
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
        if (from.equals(BODY_RELPATH)) {
            throw new ConflictException(ErrorCode.SKILL_MD_CANNOT_BE_DELETED,
                    "SKILL.md is the skill's body and cannot be moved away", "path", from);
        }

        // Moving something onto SKILL.md makes it the body, so it has to satisfy the body's rules.
        SkillMetadata declaration = to.equals(BODY_RELPATH)
                ? documentOf(blobs.get(source.blobSha256()), access.name())
                : null;

        versions.renameFile(locked.id(), from, to);
        Manifest next = versions.filesOf(locked);
        requireWithinLimits(next);
        VersionSummary after = versions.commitContent(access, locked, next, declaration);
        record(subject, access, locked, after, "move", null, from, to);
        return result(access, after);
    }
}
