package com.skillmasterai.usecase;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.common.VersionFileNotFoundException;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.ingest.IngestedFile;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.model.DraftEdit;
import com.skillmasterai.usecase.model.DraftEditResult;
import com.skillmasterai.usecase.model.DraftWriteResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The browser's one-edit route (v8 TD §接口).
 *
 * <p><strong>Why a wrapper rather than a fourth copy of the v7 write.</strong> The three existing
 * use cases already own permission, state, baseline, limits and the audit trail; this one adds the
 * one rule the browser needs and the API plane never did — the touched path may not be both a file
 * and a directory. The check reads the locked manifest and then delegates to the v7 use case, so
 * the rule has one home and the three flows keep theirs.
 */
@Component
public class WebEditDraftFileUseCase extends DraftFileWrite {

    private final WriteDraftFileUseCase writeUseCase;
    private final DeleteDraftFileUseCase deleteUseCase;
    private final MoveDraftFileUseCase moveUseCase;

    public WebEditDraftFileUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, BlobStore blobs, AuditLog audit,
            ObjectMapper objectMapper, WriteDraftFileUseCase writeUseCase,
            DeleteDraftFileUseCase deleteUseCase, MoveDraftFileUseCase moveUseCase) {
        super(namespaces, versions, metadata, blobs, audit, objectMapper);
        this.writeUseCase = writeUseCase;
        this.deleteUseCase = deleteUseCase;
        this.moveUseCase = moveUseCase;
    }

    @Transactional
    public Optional<DraftEditResult> edit(String namespaceSlug, String name, String rowId,
            String baseDigest, DraftEdit edit, AuthenticatedSubject subject) {
        Optional<Access> resolved = accessOf(namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        // The rung and the row are answered first, as they are on the v7 routes: a caller who may
        // not write this draft learns that, not that a header was missing.
        Optional<VersionSummary> opened = versions.openDraft(access, rowId);
        if (opened.isEmpty()) {
            return Optional.empty();
        }
        String baseline = requireIfMatch(baseDigest);
        VersionSummary locked = versions.lockDraft(access, rowId);
        requireBaseline(baseline, locked);
        Manifest current = versions.filesOf(locked);

        Optional<DraftWriteResult> written = switch (edit.op() == null
                ? DraftEdit.WRITE : edit.op()) {
            case DraftEdit.WRITE -> {
                String path = requireTargetPath(edit.path());
                byte[] bytes = requireText(edit.text()).getBytes(StandardCharsets.UTF_8);
                requireTree(withFile(current, path, bytes), path);
                yield writeUseCase.write(namespaceSlug, name, rowId, path, bytes, baseDigest,
                        subject);
            }
            case DraftEdit.DELETE -> deleteUseCase.delete(namespaceSlug, name, rowId,
                    requirePath(edit.path()), baseDigest, subject);
            case DraftEdit.RENAME -> {
                String from = requirePath(edit.from());
                String to = requireTargetPath(edit.to());
                requireTree(withRename(current, from, to), to);
                yield moveUseCase.move(namespaceSlug, name, rowId, from, to, baseDigest, subject);
            }
            default -> throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "the edit must be write, delete or rename",
                    List.of(new ApiError.Detail("op", "unknown_value")));
        };
        return written.map(result -> new DraftEditResult(access, result.version(),
                versions.filesOf(result.version())));
    }

    /**
     * The browser's binary upload: one file, already read from the request (v39).
     *
     * <p>The tree check runs before the store is touched, exactly as it does for a text write: a
     * path may not be both a file and a directory, and discovering that after a 100 MiB upload has
     * been stored is a waste the caller would pay for.
     */
    @Transactional
    public Optional<DraftEditResult> uploadBytes(String namespaceSlug, String name, String rowId,
            String path, byte[] bytes, boolean binary, String baseDigest,
            AuthenticatedSubject subject) {
        Optional<Manifest> current = lockForUpload(namespaceSlug, name, rowId, path, baseDigest,
                subject);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        return writeUseCase.upload(namespaceSlug, name, rowId, path, bytes, binary, baseDigest,
                        subject)
                .map(result -> new DraftEditResult(result.access(), result.version(),
                        versions.filesOf(result.version())));
    }

    @Transactional
    public Optional<DraftEditResult> uploadFile(String namespaceSlug, String name, String rowId,
            String path, java.nio.file.Path file, long size, boolean binary, String baseDigest,
            AuthenticatedSubject subject) {
        Optional<Manifest> current = lockForUpload(namespaceSlug, name, rowId, path, baseDigest,
                subject);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        return writeUseCase.writeFromFile(namespaceSlug, name, rowId, path, file, size, binary,
                        baseDigest, subject)
                .map(result -> new DraftEditResult(result.access(), result.version(),
                        versions.filesOf(result.version())));
    }

    /**
     * The checks an upload shares with an edit: visibility, rung, row, baseline, and the tree.
     *
     * <p>Deliberately before the bytes are stored. The write use case repeats them because it is
     * also reachable from the API plane; that repetition is the existing cost of two doors onto one
     * rule, not a second rule.
     */
    private Optional<Manifest> lockForUpload(String namespaceSlug, String name, String rowId,
            String path, String baseDigest, AuthenticatedSubject subject) {
        Optional<Access> resolved = accessOf(namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        requireTargetPath(path);
        Optional<VersionSummary> opened = versions.openDraft(access, rowId);
        if (opened.isEmpty()) {
            return Optional.empty();
        }
        VersionSummary locked = versions.lockDraft(access, rowId);
        requireBaseline(requireIfMatch(baseDigest), locked);
        Manifest current = versions.filesOf(locked);
        requireTree(current, path);
        return Optional.of(current);
    }

    private Manifest withFile(Manifest current, String path, byte[] bytes) {
        // Describe the file without storing it: the tree check needs the path, not the bytes, and
        // storing here would write a blob a refused edit never references.
        Map<String, ManifestEntry> entries = entriesOf(current);
        entries.put(path, new ManifestEntry(path,
                com.skillmasterai.common.Sha256Hex.of(bytes), bytes.length,
                new IngestedFile(path, bytes).isBinary()));
        return Manifest.of(new ArrayList<>(entries.values()));
    }

    private static Manifest withRename(Manifest current, String from, String to) {
        ManifestEntry source = current.find(from)
                .orElseThrow(() -> new VersionFileNotFoundException(from));
        Map<String, ManifestEntry> entries = entriesOf(current);
        entries.remove(from);
        entries.put(to, new ManifestEntry(to, source.blobSha256(), source.size(),
                source.isBinary()));
        return Manifest.of(new ArrayList<>(entries.values()));
    }

    private static Map<String, ManifestEntry> entriesOf(Manifest manifest) {
        Map<String, ManifestEntry> entries = new LinkedHashMap<>();
        for (ManifestEntry entry : manifest.entries()) {
            entries.put(entry.relpath(), entry);
        }
        return entries;
    }
}
