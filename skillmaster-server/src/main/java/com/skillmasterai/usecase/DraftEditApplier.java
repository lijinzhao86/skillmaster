package com.skillmasterai.usecase;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ConflictException;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.ingest.IngestedFile;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.usecase.model.DraftEdit;
import com.skillmasterai.common.VersionFileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies one edit to a new version's inherited manifest, before anything is stored.
 *
 * <p>Split out of {@link CreateDraftFromVersionUseCase} when that file crossed its size limit; the
 * behaviour is the use case's, unchanged. A write records a provisional entry (the canonical length,
 * a placeholder ref) and hands the bytes back as {@link Pending}, so every refusal runs before the
 * blob store is touched.
 */
final class DraftEditApplier extends DraftFileWrite {

    DraftEditApplier(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, BlobStore blobs, AuditLog audit,
            ObjectMapper objectMapper) {
        super(namespaces, versions, metadata, blobs, audit, objectMapper);
    }

    /** Applies one edit in memory; reports new metadata when it touched the root SKILL.md, and
     *  what the door rewrote on the way in (v33). */
    Applied apply(Access access, DraftEdit edit, Map<String, ManifestEntry> entries) {
        return switch (edit.op() == null ? DraftEdit.WRITE : edit.op()) {
            case DraftEdit.WRITE -> {
                String path = requireTargetPath(edit.path());
                byte[] received = requireText(edit.text()).getBytes(StandardCharsets.UTF_8);
                com.skillmasterai.modules.canon.TextCanon.Result canonical =
                        com.skillmasterai.modules.canon.TextCanon.canonicalize(path, received);
                byte[] bytes = canonical.bytes();
                boolean binary = new IngestedFile(path, bytes).isBinary();
                // Parsed here, before any store: a malformed body must be refused while nothing has
                // been written (see the ordering note in `create`).
                SkillMetadata declaration = BODY_RELPATH.equals(path)
                        ? documentOf(bytes, access.name()) : null;
                // An edit changes the bytes, not what the file is for: a script that was executable
                // stays executable after a typo fix. The placeholder digest is what gets replaced
                // once the blob is stored; the mode is the old entry's, and inventing 0644 here
                // would turn "I fixed a sentence in run.sh" into "run.sh is no longer runnable".
                ManifestEntry previous = entries.get(path);
                entries.put(path, new ManifestEntry(path, "0".repeat(64), bytes.length, binary,
                        previous == null ? ManifestEntry.DEFAULT_MODE : previous.mode()));
                yield new Applied(
                        declaration,
                        canonical.changed()
                                ? List.of(new com.skillmasterai.modules.ingest.ValidatedUpload
                                        .Normalized(path, canonical.changes()))
                                : List.of(),
                        new Pending(path, bytes, binary));
            }
            case DraftEdit.DELETE -> {
                String path = requirePath(edit.path());
                if (BODY_RELPATH.equals(path)) {
                    throw new ConflictException(ErrorCode.SKILL_MD_CANNOT_BE_DELETED,
                            "SKILL.md is the skill's body and cannot be removed", "path", path);
                }
                if (!entries.containsKey(path)) {
                    throw new VersionFileNotFoundException(path);
                }
                if (entries.size() <= 1) {
                    throw new ConflictException(ErrorCode.LAST_FILE_CANNOT_BE_DELETED,
                            "a version cannot have no files at all", "path", path);
                }
                entries.remove(path);
                yield new Applied(null, List.of(), null);
            }
            case DraftEdit.RENAME -> {
                String from = requirePath(edit.from());
                String to = requireTargetPath(edit.to());
                ManifestEntry source = entries.get(from);
                if (source == null) {
                    throw new VersionFileNotFoundException(from);
                }
                if (entries.containsKey(to)) {
                    throw new ConflictException(ErrorCode.FILE_ALREADY_EXISTS,
                            "this version already has a file at '" + to + "'", "to", to);
                }
                if (BODY_RELPATH.equals(from)) {
                    throw new ConflictException(ErrorCode.SKILL_MD_CANNOT_BE_DELETED,
                            "SKILL.md is the skill's body and cannot be moved away", "path", from);
                }
                entries.remove(from);
                // A move keeps the file: same bytes, same permissions, new path.
                entries.put(to, new ManifestEntry(to, source.blobSha256(), source.size(),
                        source.isBinary(), source.mode()));
                yield new Applied(
                        BODY_RELPATH.equals(to)
                                ? documentOf(blobs.get(source.blobSha256()), access.name())
                                : null,
                        List.of(), null);
            }
            default -> throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "the edit must be write, delete or rename",
                    List.of(new ApiError.Detail("op", "unknown_value")));
        };
    }

    /** What one edit did: the new metadata (if it touched SKILL.md), what the door rewrote, and the
     *  bytes still to store once every refusal has had its say. */
    record Applied(SkillMetadata declaration,
            List<com.skillmasterai.modules.ingest.ValidatedUpload.Normalized> normalized,
            Pending pending) {
    }

    /** A first file that is validated but not yet stored. */
    record Pending(String path, byte[] bytes, boolean binary) {
    }

}
