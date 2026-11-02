package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.ingest.IngestLimits;
import com.skillmasterai.modules.ingest.IngestedFile;
import com.skillmasterai.modules.ingest.ValidatedUpload;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.model.DraftWriteResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code skill write}: replaces or creates one file of a draft (v7 做 #6).
 *
 * <p><strong>Whole-file.</strong> There is no partial write and no append — the command is the same
 * one Claude Code's {@code Write} performs, and {@code skill edit} is built on top of it in the
 * client rather than as a second server verb (v7 TD §与 Claude Code 的 Edit / Write 对齐). The
 * server therefore has exactly one content-writing primitive, and a bug in its permission or
 * concurrency handling cannot exist in only one of two paths.
 *
 * <p><strong>Two entry points, one commit.</strong> {@link #write} takes bytes already in memory;
 * {@link #writeFromFile} takes a spooled file for an upload whose size makes holding it in the heap
 * wrong (v39). Both run the same checks and the same manifest update — the second differs only in
 * where the bytes came from and in skipping text canonicalisation, which is a whole-file operation
 * bounded to the text-processing ceiling.
 *
 * <p>A write of the root {@code SKILL.md} also updates the <em>version's</em> metadata snapshot —
 * title, description, frontmatter — because that file is the version's own declaration. The skill
 * row is untouched: it belongs to the publish flow (ADR 0049), and a draft edit must not change what
 * the consumption plane reads.
 */
@Component
public class WriteDraftFileUseCase extends DraftFileWrite {

    public WriteDraftFileUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, BlobStore blobs, AuditLog audit,
            ObjectMapper objectMapper) {
        super(namespaces, versions, metadata, blobs, audit, objectMapper);
    }

    /**
     * @param relpath where inside the skill; already checked for the obvious path problems by the
     *                caller and checked again here, because this is the authority
     * @param bytes   the file's new content, exactly as received
     * @param ifMatch the version digest the caller read, in any of the three accepted spellings
     * @return the row as it now reads, or empty when the pin names nothing this rung may see
     */
    @Transactional
    public Optional<DraftWriteResult> write(String namespaceSlug, String name, String rowId,
            String relpath, byte[] bytes, String ifMatch, AuthenticatedSubject subject) {
        return store(namespaceSlug, name, rowId, relpath, bytes, false, ifMatch, subject, false);
    }

    /**
     * One file arriving from the browser or {@code skill put} (v39).
     *
     * <p>The caller's binary probe decides whether canonicalisation runs. A binary file's bytes are
     * the author's, and rewriting them — even to remove a BOM or a CR — would corrupt content the
     * digest promises. Text takes the same {@code TextCanon} path {@link #write} does.
     */
    @Transactional
    public Optional<DraftWriteResult> upload(String namespaceSlug, String name, String rowId,
            String relpath, byte[] bytes, boolean binary, String ifMatch,
            AuthenticatedSubject subject) {
        return store(namespaceSlug, name, rowId, relpath, bytes, binary, ifMatch, subject, true);
    }

    private Optional<DraftWriteResult> store(String namespaceSlug, String name, String rowId,
            String relpath, byte[] bytes, boolean binary, String ifMatch,
            AuthenticatedSubject subject, boolean upload) {
        Optional<Access> resolved = accessOf(namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        requireTargetPath(relpath);
        Optional<VersionSummary> opened = versions.openDraft(access, rowId);
        if (opened.isEmpty()) {
            return Optional.empty();
        }

        String baseline = requireIfMatch(ifMatch);
        VersionSummary locked = versions.lockDraft(access, rowId);
        requireBaseline(baseline, locked);

        Manifest current = versions.filesOf(locked);
        requireNoCollision(current, relpath, null);
        byte[] stored = bytes;
        boolean storedBinary = binary;
        List<ValidatedUpload.Normalized> normalized = List.of();
        if (!binary) {
            // v33: one place where a write's bytes are made canonical, before anything addresses
            // them. The no-op check below compares against this, so re-sending the same text from a
            // Windows machine is a no-op rather than a new digest (ADR 0117).
            com.skillmasterai.modules.canon.TextCanon.Result canonical =
                    com.skillmasterai.modules.canon.TextCanon.canonicalize(relpath, bytes);
            stored = canonical.bytes();
            storedBinary = new IngestedFile(relpath, stored).isBinary();
            normalized = canonical.changed()
                    ? List.of(new ValidatedUpload.Normalized(relpath, canonical.changes()))
                    : List.of();
        }
        // Checked before the store is touched: a body that would be refused must not first be
        // written (or, when OSS is on, uploaded) and then rolled back.
        requireFits(current, relpath, stored.length);

        // Parsed before the blob is stored — not merely before the row is touched. With OSS on, a
        // rollback removes the `blob` row but not the object, and `deleteUnreferenced` only queues
        // objects named by rows that still exist: a refused body stored first is an object nothing
        // will ever delete (v39 audit).
        SkillMetadata declaration = relpath.equals(BODY_RELPATH)
                ? documentOf(stored, access.name())
                : null;
        BlobStore.BlobRef blob = blobs.put(stored);
        return commit(access, locked, current, relpath, blob, storedBinary, declaration,
                normalized, upload, subject);
    }

    /**
     * The streaming entry point: the request has already become a file, its digest has been
     * computed by the store, and its binary probe by the caller.
     *
     * <p>Text canonicalisation is applied up to {@link IngestLimits#STANDARD_TEXT_MAX_BYTES}, which
     * restores the pre-v39 contract for every text file the old 16 MiB skill ceiling allowed. Above
     * it the file is stored byte for byte: the operation would have to hold the whole file as a
     * {@code String}, which is exactly what streaming exists to avoid.
     *
     * @param file   a temporary file owned by the caller; it is read, never modified or deleted
     * @param binary the caller's {@code Utf8Probe} answer, stored in the manifest
     */
    @Transactional
    public Optional<DraftWriteResult> writeFromFile(String namespaceSlug, String name, String rowId,
            String relpath, Path file, long size, boolean binary, String ifMatch,
            AuthenticatedSubject subject) {
        return writeFromFile(namespaceSlug, name, rowId, relpath, file, size, binary, true,
                ifMatch, subject);
    }

    /**
     * The same streaming write, with the audit shape chosen by the caller.
     *
     * <p>The author plane carries both {@code skill write} (text) and {@code skill put} (any bytes)
     * on one route. A text write must keep v33's `op=write` / version-total shape (A10); a binary
     * upload records `add` / `overwrite` and the file's own size (A12). The caller decides from the
     * binary probe — the same signal the manifest uses.
     */
    @Transactional
    public Optional<DraftWriteResult> writeFromFile(String namespaceSlug, String name, String rowId,
            String relpath, Path file, long size, boolean binary, boolean upload, String ifMatch,
            AuthenticatedSubject subject) {
        Optional<Access> resolved = accessOf(namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        requireTargetPath(relpath);
        Optional<VersionSummary> opened = versions.openDraft(access, rowId);
        if (opened.isEmpty()) {
            return Optional.empty();
        }

        String baseline = requireIfMatch(ifMatch);
        VersionSummary locked = versions.lockDraft(access, rowId);
        requireBaseline(baseline, locked);

        Manifest current = versions.filesOf(locked);
        requireNoCollision(current, relpath, null);

        byte[] stored = null;
        List<ValidatedUpload.Normalized> normalized = List.of();
        SkillMetadata declaration = null;
        if (relpath.equals(BODY_RELPATH)) {
            byte[] body;
            if (!binary && size <= IngestLimits.STANDARD_TEXT_MAX_BYTES) {
                body = read(file);
                com.skillmasterai.modules.canon.TextCanon.Result canonical =
                        com.skillmasterai.modules.canon.TextCanon.canonicalize(relpath, body);
                stored = canonical.bytes();
                body = stored;
                normalized = canonical.changed()
                        ? List.of(new ValidatedUpload.Normalized(relpath, canonical.changes()))
                        : List.of();
            } else {
                // A body's frontmatter is a header at the start of the file. Reading a bounded
                // prefix keeps a pathological 100 MiB SKILL.md from becoming a heap spike while
                // still parsing the declaration it carries.
                body = readPrefix(file);
            }
            // Parsed before the blob is stored: a malformed body must not leave an OSS object with
            // no row pointing at it.
            declaration = documentOf(body, access.name());
        } else if (!binary && size <= IngestLimits.STANDARD_TEXT_MAX_BYTES) {
            byte[] raw = read(file);
            com.skillmasterai.modules.canon.TextCanon.Result canonical =
                    com.skillmasterai.modules.canon.TextCanon.canonicalize(relpath, raw);
            stored = canonical.bytes();
            normalized = canonical.changed()
                    ? List.of(new ValidatedUpload.Normalized(relpath, canonical.changes()))
                    : List.of();
        }

        requireFits(current, relpath, stored == null ? size : stored.length);
        BlobStore.BlobRef blob = stored == null ? blobs.put(file, size) : blobs.put(stored);
        return commit(access, locked, current, relpath, blob, binary, declaration, normalized,
                upload, subject);
    }

    private Optional<DraftWriteResult> commit(Access access, VersionSummary locked, Manifest current,
            String relpath, BlobStore.BlobRef blob, boolean binary, SkillMetadata declaration,
            List<ValidatedUpload.Normalized> normalized, boolean upload,
            AuthenticatedSubject subject) {
        Optional<ManifestEntry> existing = current.find(relpath);

        // Writing the same bytes over the same file is the same state reached twice: no row change,
        // no audit, and — because nothing is written — no chance of the 409 the next check would
        // otherwise raise against the row's own digest.
        if (existing.isPresent() && existing.get().blobSha256().equals(blob.sha256Hex())
                && existing.get().isBinary() == binary) {
            return unchanged(access, locked);
        }

        versions.putFile(locked.id(), new ManifestEntry(relpath, blob.sha256Hex(), blob.size(),
                binary));
        Manifest next = versions.filesOf(locked);
        requireWithinLimits(next);

        VersionSummary after = versions.commitContent(access, locked, next, declaration);
        // An upload records what this file added or replaced (A12), not the version's total, which
        // answers a different question and cannot say "how much did this operation write".
        String op = upload ? (existing.isPresent() ? "overwrite" : "add") : "write";
        long bytes = upload ? blob.size() : locked.totalBytes();
        record(subject, access, locked, after, op, relpath, null, null, normalized, bytes);
        return result(access, after);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the spooled body", e);
        }
    }

    /** The header portion of a file whose full size need not enter the heap. */
    private static byte[] readPrefix(Path file) {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            // Four extra bytes so the cut can be backed up to a character boundary.
            return com.skillmasterai.modules.ingest.Utf8Probe.cutIncompleteTail(
                    in.readNBytes((int) IngestLimits.STANDARD_TEXT_MAX_BYTES + 4));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the spooled body", e);
        }
    }
}
