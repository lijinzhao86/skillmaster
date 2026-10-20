package com.skillmasterai.modules.ingest;

import com.skillmasterai.modules.ingest.internal.ZipReader;
import java.util.List;

/**
 * M5: turns uploaded bytes into a {@link SkillUpload}, or refuses them.
 *
 * <p><strong>The server is the only authority on this.</strong> The CLI validates too, so that an
 * author gets a fast answer, but its verdict is advisory — ADR 0011's constraint is explicit that
 * client-side validation can never be the trusted one, because the client is not the thing being
 * protected. Everything here runs again on arrival.
 *
 * <p>Structure of an upload, and the rule for each part:
 * <ol>
 *   <li>A zip of one skill's directory tree, read by {@link ZipReader} (which owns the hostile
 *       cases: symlinks, traversal, bombs).</li>
 *   <li>A single leading directory is stripped if every entry shares it — {@code zip -r x.zip
 *       lark/} is the natural way to make one, and rejecting it would be pedantry. When one is
 *       stripped, its name must equal the frontmatter {@code name}: that is §1.3's MUST, and the
 *       only moment it can be checked, since the zip's directory structure is not stored.</li>
 *   <li>{@code SKILL.md} at the skill root, with a frontmatter block.</li>
 *   <li>{@code name} and {@code description} present — both are required by the standard, and
 *       {@code description} is what search runs on, so an empty one is a skill nobody can find.</li>
 * </ol>
 */
public final class SkillUploadValidator {

    public static final String SKILL_MD = "SKILL.md";

    /** Long enough for any real name, short enough to bound what a URL or an index has to carry. */

    private final IngestLimits limits;

    public SkillUploadValidator(IngestLimits limits) {
        this.limits = limits;
    }

    public SkillUpload validate(byte[] zip) {
        List<IngestedFile> files = ZipReader.read(zip, limits);
        StrippedRoot stripped = stripSingleRootDirectory(files);

        IngestedFile skillMd = stripped.files().stream()
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

        // `version:` is deliberately not read. Since v6 the number is chosen at publish, so a
        // malformed value here must not block a submission — the field still travels in the
        // frontmatter copy, which is the only promise made about it (ADR 0055).
        return new SkillUpload(document.name(), document.title(), document.description(),
                document.frontmatter(), stripped.files());
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
