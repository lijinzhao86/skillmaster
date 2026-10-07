package com.skillmasterai.modules.distribution;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.version.Caller;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillSnapshot;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import java.util.Optional;

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
     *                  caller may do with it travels separately, in the {@code Caller}
     * @param pin       which version; {@code latest} re-reads the pointer, a pin does not
     */
    public Optional<SkillDetail> detailOf(Namespace namespace, Caller caller, String name,
            VersionPin pin) {
        return readableIn(namespace, caller, name, pin).map(snapshot -> new SkillDetail(
                snapshot.skillId(),
                snapshot.name(),
                snapshot.title(),
                snapshot.description(),
                namespace.slug(),
                namespace.title(),
                snapshot.visibility(),
                snapshot.frontmatterJson(),
                snapshot.version(),
                snapshot.digest(),
                snapshot.stateAt(),
                snapshot.fileCount(),
                snapshot.totalBytes(),
                snapshot.isLatest(),
                snapshot.manifest().entries().stream()
                        .map(entry -> new SkillDetail.File(
                                entry.relpath(), entry.blobSha256(), entry.size(), entry.isBinary()))
                        .toList()));
    }

    /**
     * L2: the original {@code SKILL.md} bytes, frontmatter included.
     *
     * <p>Byte for byte what was uploaded. §4.2's "托管要保真" and ADR 0005's digest both depend on
     * this path never rewriting anything — no BOM stripping, no line-ending normalisation, no
     * re-encoding. The bytes served are the bytes whose digest the manifest advertises.
     */
    public Optional<byte[]> bodyOf(Namespace namespace, Caller caller, String name,
            VersionPin pin) {
        return readableIn(namespace, caller, name, pin).map(snapshot -> blobs.get(
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
    public Optional<byte[]> authorBodyOf(Namespace namespace, Caller caller, String name,
            VersionPin pin) {
        return versions.authorSnapshot(namespace.id(), name, pin, caller)
                .map(snapshot -> blobs.get(entryFor(snapshot, BODY_RELPATH).blobSha256()));
    }

    /**
     * L3: one file's original bytes, chosen by exact {@code relpath} match.
     *
     * <p>The empty {@code Optional} is "this address names nothing". A version that resolved but
     * does not list the relpath is a different answer and comes back as {@link NotFoundInManifest}
     * — see {@link FileLookup}.
     */
    public Optional<FileLookup> fileOf(Namespace namespace, Caller caller, String name,
            VersionPin pin, String relpath) {
        return fileFrom(readableIn(namespace, caller, name, pin), relpath);
    }

    /**
     * L3 for the author's own plane: a file of a version that need not be published.
     *
     * <p>Split from {@link #fileOf} for the same reason as {@link #authorBodyOf}, and needed for the
     * same kind of reason: the browser shows a version's file list, and every entry but the body has
     * no other way to be read — the API plane's L3 resolves only published versions, so a draft's
     * {@code references/} would be listed and unopenable.
     */
    public Optional<FileLookup> authorFileOf(Namespace namespace, Caller caller, String name,
            VersionPin pin, String relpath) {
        return fileFrom(versions.authorSnapshot(namespace.id(), name, pin, caller), relpath);
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
    private Optional<SkillSnapshot> readableIn(Namespace namespace, Caller caller, String name,
            VersionPin pin) {
        return versions.liveSnapshot(namespace.id(), name, pin, caller);
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
}
