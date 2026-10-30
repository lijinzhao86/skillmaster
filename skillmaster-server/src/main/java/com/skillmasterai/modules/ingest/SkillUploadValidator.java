package com.skillmasterai.modules.ingest;

import com.skillmasterai.modules.canon.Junk;
import com.skillmasterai.modules.canon.PathCanon;
import com.skillmasterai.modules.canon.TextCanon;
import com.skillmasterai.modules.ingest.internal.ZipReader;
import java.util.ArrayList;
import java.util.List;

/**
 * M5: turns uploaded bytes into a {@link ValidatedUpload}, or refuses them.
 *
 * <p><strong>The server is the only authority on this.</strong> The CLI validates too, so that an
 * author gets a fast answer, but its verdict is advisory — ADR 0011's constraint is explicit that
 * client-side validation can never be the trusted one, because the client is not the thing being
 * protected. Everything here runs again on arrival.
 *
 * <p>Structure of an upload, and the rule for each part:
 * <ol>
 *   <li>A zip of one skill's directory tree, read by {@link ZipReader} (which owns the hostile
 *       cases: symlinks, traversal, bombs). The reader is given one extra file and 64 KiB of room
 *       so a package may carry the metadata sidecar; it is not told what that path is.</li>
 *   <li>A single leading directory is stripped if every entry shares it — {@code zip -r x.zip
 *       lark/} is the natural way to make one, and rejecting it would be pedantry. When one is
 *       stripped, its name must equal the frontmatter {@code name}: that is §1.3's MUST, and the
 *       only moment it can be checked, since the zip's directory structure is not stored.</li>
 *   <li>The reserved path is taken out of the content set and parsed as version metadata
 *       (ADR 0069). A file there that is not metadata-shaped is refused rather than treated as
 *       content — the path belongs to the platform.</li>
 *   <li>{@code SKILL.md} at the skill root, with a frontmatter block.</li>
 *   <li>{@code name} and {@code description} present — both are required by the standard, and
 *       {@code description} is what search runs on, so an empty one is a skill nobody can find.</li>
 *   <li>The <strong>content</strong> ceilings, re-applied after the sidecar is gone. This is what
 *       makes 512 files plus a sidecar legal while 513 content files are not.</li>
 * </ol>
 */
public final class SkillUploadValidator {

    public static final String SKILL_MD = "SKILL.md";

    private final IngestLimits limits;

    public SkillUploadValidator(IngestLimits limits) {
        this.limits = limits;
    }

    public ValidatedUpload validate(byte[] zip) {
        List<IngestedFile> files;
        try {
            files = ZipReader.read(zip, limits.withRoomForSidecar());
        } catch (IngestException refused) {
            throw contentCeilingOf(refused);
        }

        // v33: archive artefacts go first, and before the single-root strip — a macOS-made zip puts
        // `__MACOSX/` at the same level as the skill's own folder, which would otherwise hide the
        // fact that every remaining entry shares one root.
        List<String> dropped = new ArrayList<>();
        List<IngestedFile> kept = new ArrayList<>(files.size());
        for (IngestedFile file : files) {
            if (Junk.isJunk(file.relpath())) {
                dropped.add(file.relpath());
            } else {
                kept.add(file);
            }
        }
        if (kept.isEmpty()) {
            throw new IngestException("the upload contains no files", "file", "empty_archive");
        }

        StrippedRoot stripped = stripSingleRootDirectory(kept);

        // v33: every name normalized before anything compares names, so the same name in two
        // encodings is one name and the colliding pair can be named in the refusal.
        List<IngestedFile> normalizedFiles = new ArrayList<>(stripped.files().size());
        List<String> paths = new ArrayList<>(stripped.files().size());
        for (IngestedFile file : stripped.files()) {
            String path = PathCanon.normalize(file.relpath());
            normalizedFiles.add(new IngestedFile(path, file.bytes()));
            paths.add(path);
        }
        requireUsableNames(paths);

        IngestedFile sidecarFile = normalizedFiles.stream()
                .filter(file -> ReservedPaths.isSidecar(file.relpath()))
                .findFirst()
                .orElse(null);
        List<IngestedFile> rawContent = normalizedFiles.stream()
                .filter(file -> !ReservedPaths.isSidecar(file.relpath()))
                .toList();

        // Parsed before SKILL.md is looked for, on purpose: a package whose own declaration is
        // malformed should hear about that first, not about a missing file it may well have.
        SkillSidecar sidecar = null;
        if (sidecarFile != null) {
            if (!SkillSidecar.looksLikeMetadata(sidecarFile.bytes())) {
                throw new IngestException(
                        "the package has a file at '.skillmaster/metadata.json', a path reserved"
                                + " for the platform's version metadata",
                        sidecarFile.relpath(), "reserved_path");
            }
            sidecar = SkillSidecar.parse(sidecarFile.bytes());
        }

        // v33: the content's bytes are made canonical before anything reads them, so SKILL.md's
        // frontmatter never has to cope with a BOM or CRLF, and two authors' copies of the same text
        // arrive as one digest (ADR 0117). The sidecar is metadata rather than content and is parsed
        // as received.
        List<ValidatedUpload.Normalized> normalized = new ArrayList<>();
        List<IngestedFile> content = new ArrayList<>(rawContent.size());
        for (IngestedFile file : rawContent) {
            TextCanon.Result result = TextCanon.canonicalize(file.relpath(), file.bytes());
            if (result.changed()) {
                normalized.add(new ValidatedUpload.Normalized(file.relpath(), result.changes()));
            }
            content.add(new IngestedFile(file.relpath(), result.bytes()));
        }

        IngestedFile skillMd = content.stream()
                .filter(file -> file.relpath().equals(SKILL_MD))
                .findFirst()
                .orElseThrow(() -> new IngestException(
                        "the skill has no SKILL.md at its root", SKILL_MD, "missing_skill_md"));

        // The document's own rules — required name and description, a usable name, the title
        // fallback — live in SkillDocument, because v7's root-SKILL.md write asks the same question
        // about the same bytes (v7 TD §M5 复用).
        SkillDocument.Document document = SkillDocument.parse(skillMd.bytes());
        if (stripped.directoryName() != null && !stripped.directoryName().equals(document.name())) {
            throw new IngestException(
                    "the skill's directory is named '" + stripped.directoryName()
                            + "' but its frontmatter says name: " + document.name(),
                    "name", "name_does_not_match_directory");
        }

        if (content.size() > limits.maxFiles()) {
            throw new IngestException(
                    "the upload has more than " + limits.maxFiles() + " files",
                    "files", "too_many_files");
        }
        long totalBytes = content.stream().mapToLong(IngestedFile::size).sum();
        if (totalBytes > limits.maxTotalBytes()) {
            throw new IngestException(
                    "the upload exceeds " + limits.maxTotalBytes() + " bytes uncompressed",
                    "files", "too_large");
        }

        // `version:` is deliberately not read. Since v6 the number is chosen at publish, so a
        // malformed value here must not block a submission — the field still travels in the
        // frontmatter copy, which is the only promise made about it (ADR 0055).
        SkillUpload upload = new SkillUpload(document.name(), document.title(),
                document.description(), document.frontmatter(), content);
        return new ValidatedUpload(upload, sidecar, normalized, dropped);
    }

    /**
     * v33's names: each path usable on every system, and no two of them one file.
     *
     * <p>Every problem is reported, not the first — the author fixes one package, not one name per
     * round trip. A collision contributes two details, one per path, because the sentence names
     * both (v33 PRD §措辞).
     */
    private static void requireUsableNames(List<String> paths) {
        List<com.skillmasterai.common.ApiError.Detail> details = new ArrayList<>();
        for (String path : paths) {
            PathCanon.check(path).ifPresent(issue -> details.add(
                    new com.skillmasterai.common.ApiError.Detail(issue.field(), issue.issue())));
        }
        for (PathCanon.Collision collision : PathCanon.collisions(paths)) {
            details.add(new com.skillmasterai.common.ApiError.Detail(collision.a(),
                    collision.issue()));
            details.add(new com.skillmasterai.common.ApiError.Detail(collision.b(),
                    collision.issue()));
        }
        if (!details.isEmpty()) {
            throw new IngestException(
                    "the upload has file names that are not usable on every system", details);
        }
    }

    /**
     * Names the product's ceiling, not the reader's allowance.
     *
     * <p>The reader is given one extra entry and {@link SkillSidecar#MAX_BYTES} so a package may
     * carry the sidecar. If it refuses anyway, the package is over the content ceilings too — and
     * the number in its sentence would be the allowance (513 files, 16 MiB + 64 KiB) rather than the
     * rule, which is a refusal that tells a person to trim to a size that is still refused. Every
     * other refusal passes through untouched, because it is not about a ceiling.
     */
    private IngestException contentCeilingOf(IngestException refused) {
        List<com.skillmasterai.common.ApiError.Detail> details = refused.details();
        if (details.size() == 1) {
            String issue = details.getFirst().issue();
            if ("too_many_files".equals(issue)) {
                return new IngestException(
                        "the upload has more than " + limits.maxFiles() + " files",
                        "files", "too_many_files");
            }
            if ("too_large".equals(issue)) {
                return new IngestException(
                        "the upload exceeds " + limits.maxTotalBytes() + " bytes uncompressed",
                        "files", "too_large");
            }
        }
        return refused;
    }

    private record StrippedRoot(List<IngestedFile> files, String directoryName) {
    }

    /**
     * Removes a single shared leading directory, if there is one, and reports its name.
     *
     * <p>Only when <em>every</em> entry has at least one path segment. A zip holding {@code
     * SKILL.md} beside {@code references/} has no common root — its first segments are the file
     * name itself and {@code references} — and stripping there would move files the author placed
     * at the root.
     */
    private static StrippedRoot stripSingleRootDirectory(List<IngestedFile> files) {
        boolean everyEntryIsNested = files.stream().allMatch(file -> file.relpath().contains("/"));
        if (!everyEntryIsNested) {
            return new StrippedRoot(files, null);
        }
        String root = files.getFirst().relpath().substring(0, files.getFirst().relpath().indexOf('/'));
        boolean allShareRoot = files.stream()
                .allMatch(file -> file.relpath().startsWith(root + "/"));
        if (!allShareRoot) {
            return new StrippedRoot(files, null);
        }
        int prefix = root.length() + 1;
        List<IngestedFile> stripped = files.stream()
                .map(file -> new IngestedFile(file.relpath().substring(prefix), file.bytes()))
                .toList();
        return new StrippedRoot(stripped, root);
    }

}
