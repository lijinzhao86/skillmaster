package com.skillmasterai.usecase;

import com.skillmasterai.common.ConflictException;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
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
 * {@code skill rm}: removes one file of a draft (v7 做 #8).
 *
 * <p><strong>Two refusals, both about the skill still making sense.</strong> The root
 * {@code SKILL.md} cannot go — a skill without one is not a skill, and the next publish would fail a
 * validation the author cannot see. And the last file cannot go — an empty version is a version no
 * client could invoke. A nested {@code references/SKILL.md} is an ordinary file and both rulings
 * leave it alone.
 *
 * <p>The blob the file pointed at is not deleted here. Content addressing means another version (or
 * another path) may still hold it, and deciding that is the sweep's job — under its grace period
 * (v7 TD §blob 回收). Removing the reference is what this operation owns.
 */
@Component
public class DeleteDraftFileUseCase extends DraftFileWrite {

    public DeleteDraftFileUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, BlobStore blobs, AuditLog audit,
            ObjectMapper objectMapper) {
        super(namespaces, versions, metadata, blobs, audit, objectMapper);
    }

    @Transactional
    public Optional<DraftWriteResult> delete(String namespaceSlug, String name, String rowId,
            String relpath, String ifMatch, AuthenticatedSubject subject) {
        Optional<Access> resolved = accessOf(namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        requirePath(relpath);
        Optional<VersionSummary> opened = versions.openDraft(access, rowId);
        if (opened.isEmpty()) {
            return Optional.empty();
        }

        String baseline = requireIfMatch(ifMatch);
        VersionSummary locked = versions.lockDraft(access, rowId);
        requireBaseline(baseline, locked);

        Manifest current = versions.filesOf(locked);
        if (relpath.equals(BODY_RELPATH)) {
            throw new ConflictException(ErrorCode.SKILL_MD_CANNOT_BE_DELETED,
                    "SKILL.md is the skill's body and cannot be removed", "path", relpath);
        }
        if (current.find(relpath).isEmpty()) {
            throw new com.skillmasterai.common.VersionFileNotFoundException(relpath);
        }
        if (current.fileCount() <= 1) {
            throw new ConflictException(ErrorCode.LAST_FILE_CANNOT_BE_DELETED,
                    "a version cannot have no files at all", "path", relpath);
        }

        versions.deleteFile(locked.id(), relpath);
        Manifest next = versions.filesOf(locked);
        VersionSummary after = versions.commitContent(access, locked, next, null);
        record(subject, access, locked, after, "delete", relpath, null, null);
        return result(access, after);
    }
}
