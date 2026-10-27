package com.skillmasterai.modules.distribution;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.ingest.ReservedPaths;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillSnapshot;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * M9: serving a skill at its three levels of verbosity.
 *
 * <p>L1 is the listing (M8), L2 the body, L3 a single file — and this class serves L1's detail
 * shape plus L2 and L3. All three are the same question with a different answer size, which is why
 * they share one resolver: {@link #readableIn} is the only place a skill is turned into something a
 * caller may look at.
 *
 * <p><strong>Content is fetched by digest, never by path.</strong> L3 looks a {@code relpath} up in
 * the stored manifest and reads the blob the manifest names. Nothing here resolves a path, joins a
 * path, or touches a filesystem — §4.2 requires the exact-match rule, and obeying it by using a
 * lookup rather than a sanitiser is the difference between a rule that cannot be broken and one
 * that has to be remembered.
 */
public final class SkillDistributionService {

    /** The body a skill is required to have; M5 refuses an upload without one (§1.3). */
    private static final String BODY_RELPATH = "SKILL.md";

    private final SkillVersionService versions;
    private final BlobStore blobs;

    public SkillDistributionService(SkillVersionService versions, BlobStore blobs) {
        this.versions = versions;
        this.blobs = blobs;
    }

    /**
     * @param namespace the namespace the address named, which is not necessarily one the
     *                  caller owns — a shared skill lives in somebody else's (ADR 0034). What the
     *                  caller may do with it travels separately, in the {@code Access}
     * @param pin       which version; {@code latest} re-reads the pointer, a pin does not
     */
    public Optional<SkillDetail> detailOf(Namespace namespace, Access access, VersionPin pin) {
        // The detail carries the manifest, so it is the one read on this plane that needs
        // `browse`; body and single-file stay at `invoke` (ADR 0047 §决定 1).
        return detailedIn(access, pin)
                .map(snapshot -> toDetail(snapshot, namespace, access));
    }

    /**
     * L1 alone: the metadata, without the manifest being part of the answer.
     *
     * <p>It resolves through {@link #readableIn}, which is the {@code invoke} rung. The manifest
     * exists inside the snapshot either way — it is how a version is stored — but the response
     * built from this does not carry it, which is the whole of what `/metadata` promises: the name
     * and description are enough to decide whether to invoke; the file list is the enumeration
     * {@code invoke} must not have.
     */
    public Optional<SkillDetail> metaOf(Namespace namespace, Access access, VersionPin pin) {
        return readableIn(access, pin)
                .map(snapshot -> toDetail(snapshot, namespace, access));
    }

    private SkillDetail toDetail(SkillSnapshot snapshot, Namespace namespace, Access access) {
        return new SkillDetail(
                snapshot.skillId(),
                snapshot.name(),
                snapshot.title(),
                snapshot.description(),
                namespace.slug(),
                namespace.title(),
                access.visibility(),
                snapshot.frontmatterJson(),
                snapshot.version(),
                snapshot.digest(),
                // The first publish, not the latest state change: a restored version must not
                // report its restore date as when it was published.
                snapshot.firstPublishedAt(),
                snapshot.fileCount(),
                snapshot.totalBytes(),
                snapshot.isLatest(),
                // The same `Access` that resolved the read, so this is not a second lookup — the
                // rung and the version came from one statement in M12 (ADR 0053).
                access.atLeast(GrantRole.WRITE),
                // v23: the owner is above the ladder rather than on it (`Access`), and every action
                // that has a CLI command is the same at admin — so the owner is reported as admin.
                access.owns() ? GrantRole.ADMIN : access.rung(),
                GrantRole.permissionsOf(access.owns() ? GrantRole.ADMIN : access.rung()),
                snapshot.manifest().entries().stream()
                        .map(entry -> new SkillDetail.File(
                                entry.relpath(), entry.blobSha256(), entry.size(), entry.isBinary()))
                        .toList());
    }

    /**
     * L2: the original {@code SKILL.md} bytes, frontmatter included.
     *
     * <p>Byte for byte what was uploaded. §4.2's "托管要保真" and ADR 0005's digest both depend on
     * this path never rewriting anything — no BOM stripping, no line-ending normalisation, no
     * re-encoding. The bytes served are the bytes whose digest the manifest advertises.
     */
    public Optional<byte[]> bodyOf(Namespace namespace, Access access, VersionPin pin) {
        return readableIn(access, pin).map(snapshot -> blobs.get(
                entryFor(snapshot, BODY_RELPATH).blobSha256()));
    }

    /**
     * L2 for the author's own plane: the same bytes, from a version that need not be published.
     *
     * <p>Split from {@link #bodyOf} rather than given a flag, for the reason M7 splits its own two
     * entry points: a consumption-plane caller must not be able to reach the wider one by passing a
     * boolean. The bytes themselves are read identically, which is the point — a draft's
     * {@code SKILL.md} is the file the author wrote, and there is nothing draft-shaped about it.
     */
    public Optional<byte[]> authorBodyOf(Namespace namespace, Access access, VersionPin pin) {
        return versions.authorSnapshot(access, pin)
                .map(snapshot -> blobs.get(entryFor(snapshot, BODY_RELPATH).blobSha256()));
    }

    /**
     * L3: one file's original bytes, chosen by exact {@code relpath} match.
     *
     * <p>The empty {@code Optional} is "this address names nothing". A version that resolved but
     * does not list the relpath is a different answer and comes back as {@link NotFoundInManifest}
     * — see {@link FileLookup}.
     */
    public Optional<FileLookup> fileOf(Namespace namespace, Access access, VersionPin pin,
            String relpath) {
        return fileFrom(readableIn(access, pin), relpath);
    }

    /**
     * L3 for the author's own plane: a file of a version that need not be published.
     *
     * <p>Split from {@link #fileOf} for the same reason as {@link #authorBodyOf}, and needed for the
     * same kind of reason: the browser shows a version's file list, and every entry but the body has
     * no other way to be read — the API plane's L3 resolves only published versions, so a draft's
     * {@code references/} would be listed and unopenable.
     */
    public Optional<FileLookup> authorFileOf(Namespace namespace, Access access, VersionPin pin,
            String relpath) {
        return fileFrom(versions.authorSnapshot(access, pin), relpath);
    }

    /**
     * One file for a response that streams, with the version digest it belongs to.
     *
     * <p>Split from {@link #fileOf} rather than given a flag, for the reason M7 splits its entry
     * points: a streaming read is the only one a CLI uses, and it must not be possible to reach the
     * buffered path by passing a boolean. The consumer and author planes share this shape and differ
     * only in which resolver found the snapshot.
     */
    public Optional<StreamLookup> streamFileOf(Namespace namespace, Access access, VersionPin pin,
            String relpath) {
        return streamFrom(readableIn(access, pin), relpath);
    }

    /** The author plane's streaming read: a version that need not be published. */
    public Optional<StreamLookup> authorStreamFileOf(Namespace namespace, Access access,
            VersionPin pin, String relpath) {
        return streamFrom(versions.authorSnapshot(access, pin), relpath);
    }

    /**
     * One version as a zip on disk (ADR 0068).
     *
     * <p><strong>The package is one manifest, byte for byte.</strong> Entries are written in the
     * manifest's own order and carry its relpaths; the digest returned is the manifest's, so a
     * client comparing the {@code ETag} with its own digest of the archive's contents is comparing
     * one fact. The bytes are pulled from {@link BlobStore#open} one file at a time — nothing here
     * buffers the version.
     *
     * <p><strong>The sidecar is the caller's decision.</strong> M9 knows nothing about version
     * names or reserved paths; the use case decides whether to render metadata at all (it does not
     * when the manifest already has a file at that path) and hands the bytes over.
     *
     * <p><strong>Failure happens before anything is returned.</strong> A blob that cannot be read,
     * or a full disk, deletes the partial file here and throws — the controller never receives an
     * artifact it cannot stream, so the client gets an error envelope instead of half a zip.
     */
    public ArchiveArtifact archive(Manifest manifest, byte[] sidecar, String filename) {
        Path temp = null;
        try {
            temp = Files.createTempFile("skillmaster-archive-", ".zip");
            try (OutputStream file = Files.newOutputStream(temp);
                    ZipOutputStream zip = new ZipOutputStream(file)) {
                for (ManifestEntry entry : manifest.entries()) {
                    zip.putNextEntry(new ZipEntry(entry.relpath()));
                    try (InputStream in = blobs.open(entry.blobSha256())) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                }
                if (sidecar != null) {
                    zip.putNextEntry(new ZipEntry(ReservedPaths.SIDECAR));
                    zip.write(sidecar);
                    zip.closeEntry();
                }
            }
            return new ArchiveArtifact(temp, filename, manifest.digest(), Files.size(temp));
        } catch (IOException | RuntimeException | Error e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // The original failure is the one worth reporting.
                }
            }
            if (e instanceof IOException io) {
                throw new UncheckedIOException(
                        "building the archive for '" + filename + "' failed", io);
            }
            // `Error` is caught only to remove the temp file; it is rethrown untouched (v9 TD §架构).
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw (Error) e;
        }
    }

    private Optional<StreamLookup> streamFrom(Optional<SkillSnapshot> snapshot, String relpath) {
        return snapshot.map(resolved -> resolved.manifest().find(relpath)
                .<StreamLookup>map(entry -> new StreamLookup.Found(new StreamedFile(
                        entry, resolved.digest(), resolved.fileCount(),
                        () -> blobs.open(entry.blobSha256()))))
                .orElseGet(() -> new StreamLookup.NotFoundInManifest(relpath)));
    }

    private Optional<FileLookup> fileFrom(Optional<SkillSnapshot> snapshot, String relpath) {
        return snapshot.map(resolved -> resolved.manifest().find(relpath)
                .<FileLookup>map(entry -> new FileLookup.Found(
                        new StoredFile(entry, blobs.get(entry.blobSha256()))))
                .orElseGet(() -> new FileLookup.NotFoundInManifest(relpath)));
    }

    /**
     * Resolves a version of a skill the caller may read, or nothing.
     *
     * <p>Handing the namespace to M7 rather than checking its answer afterwards is what makes "not
     * yours" and "no such skill" a single outcome — see {@link SkillVersionService#liveSnapshot}.
     * The pin travels with it because choosing a version is equally M7's: {@code current_version_id}
     * and every {@code skill_version} row are M7's own columns.
     */
    private Optional<SkillSnapshot> readableIn(Access access, VersionPin pin) {
        return versions.liveSnapshot(access, pin);
    }

    /** The same resolution, at the one rung that may receive the manifest. */
    private Optional<SkillSnapshot> detailedIn(Access access, VersionPin pin) {
        return versions.detailedSnapshot(access, pin);
    }

    private static ManifestEntry entryFor(SkillSnapshot snapshot, String relpath) {
        return snapshot.manifest().find(relpath).orElseThrow(() ->
                // M5 requires SKILL.md at the skill root, so every published version has one. Its
                // absence means the manifest was written by something other than the publish path,
                // and inventing a 404 for it would hide that.
                new IllegalStateException("version " + snapshot.digest()
                        + " of skill " + snapshot.skillId() + " has no " + relpath));
    }

    /**
     * L3's outcome, which has two distinguishable nothings that §4.1 gives two codes.
     *
     * <p>{@code skill_not_found} is an address that resolved to no version at all — no such skill,
     * not the caller's, soft-deleted, or a version that does not exist. {@code file_not_found} is a
     * version that <em>did</em> resolve and whose manifest does not list this relpath. Folding the
     * second into the first leaves a client written against §4.1's table waiting for a code the
     * server never sends.
     *
     * <p>It discloses nothing: a caller can only reach this through a version it may already read,
     * and the L1 detail endpoint hands it every relpath in that manifest anyway.
     */
    public sealed interface FileLookup {

        /** The bytes, and the manifest entry that named them. */
        record Found(StoredFile file) implements FileLookup {
        }

        /** A version resolved; its manifest has no such relpath. */
        record NotFoundInManifest(String relpath) implements FileLookup {
        }
    }

    /** A file's bytes together with the entry that named them. */
    public record StoredFile(ManifestEntry entry, byte[] bytes) {
    }

    /** Streaming's two nothings, the same distinction {@link FileLookup} keeps. */
    public sealed interface StreamLookup {

        record Found(StreamedFile file) implements StreamLookup {
        }

        record NotFoundInManifest(String relpath) implements StreamLookup {
        }
    }

    /**
     * @param entry       where the file sits and how big it is, which is what the response's
     *                    {@code Content-Length} is taken from — never from the bytes, which have
     *                    not been read yet
     * @param digest      the version's digest, the value of the response's {@code ETag}
     * @param open        a fresh stream over the blob; called when the body is written, so an
     *                    early disconnect never opens one
     */
    public record StreamedFile(ManifestEntry entry, String digest, int fileCount,
            java.util.function.Supplier<java.io.InputStream> open) {
    }
}
