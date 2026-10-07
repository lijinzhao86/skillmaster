package com.skillmasterai.modules.ingest;

import com.skillmasterai.common.SemVer;
import com.skillmasterai.modules.ingest.internal.ZipReader;
import java.util.List;
import java.util.Map;

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
    private static final int MAX_NAME_LENGTH = 64;

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

        Map<String, Object> frontmatter = FrontmatterParser.parse(skillMd.bytes());
        String name = requiredText(frontmatter, "name");
        String description = requiredText(frontmatter, "description");
        // The blank filter is what makes `title: ""` fall back the way an absent title does: a
        // present-but-empty title would otherwise reach every search card as an empty line.
        String title = optionalText(frontmatter, "title").filter(value -> !value.isBlank())
                .orElse(name);

        requireUsableName(name);
        if (stripped.directoryName() != null && !stripped.directoryName().equals(name)) {
            throw new IngestException(
                    "the skill's directory is named '" + stripped.directoryName()
                            + "' but its frontmatter says name: " + name,
                    "name", "name_does_not_match_directory");
        }

        return new SkillUpload(name, title, description, versionOf(frontmatter), frontmatter,
                stripped.files());
    }

    /**
     * The author's declared version, or null when they declared none.
     *
     * <p>Optional on purpose. The Agent Skills standard has no {@code version} field — it is a
     * publisher extension (Feishu ships one) — and a submission that declares none is ordinary: the
     * version is then addressable only by its digest, which is the same bargain the host's own plugin
     * loader makes when {@code version} is omitted and it falls back to the commit SHA (ADR 0033).
     *
     * <p>Two refusals, and the messages differ because the mistakes differ. A value that is not text
     * is almost always an unquoted version — YAML reads {@code version: 1.0} as a float — so the
     * message says how to fix it rather than repeating {@link #optionalText}'s generic complaint. A
     * string that is not a semver is a genuine typo, and the answer is the grammar.
     */
    private static String versionOf(Map<String, Object> frontmatter) {
        Object value = frontmatter.get("version");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IngestException(
                    "SKILL.md's 'version' must be a quoted semver string. YAML reads `version: "
                            + value + "` as a " + value.getClass().getSimpleName().toLowerCase()
                            + ", so write it as version: \"" + value + "\"",
                    "version", "version_not_text");
        }
        if (!SemVer.isValid(text)) {
            throw new IngestException(
                    "SKILL.md's 'version' is not a semantic version: '" + text
                            + "'. It must be MAJOR.MINOR.PATCH, optionally with -prerelease"
                            + " and +build — 1.0.0, not 1.0 or v1.0.0",
                    "version", "version_not_semver");
        }
        return text;
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

    private static String requiredText(Map<String, Object> frontmatter, String field) {
        return optionalText(frontmatter, field)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IngestException(
                        "SKILL.md's frontmatter has no '" + field + "'", field, "required_field_missing"));
    }

    private static java.util.Optional<String> optionalText(Map<String, Object> frontmatter, String field) {
        Object value = frontmatter.get(field);
        // A nested mapping or a list where a string belongs is rejected rather than stringified:
        // String.valueOf on a Map produces something that looks like a value and is not one.
        if (value != null && !(value instanceof String)) {
            throw new IngestException(
                    "SKILL.md's frontmatter field '" + field + "' must be text, but is a "
                            + value.getClass().getSimpleName(),
                    field, "field_not_text");
        }
        return java.util.Optional.ofNullable((String) value);
    }

    /**
     * Refuses a name that cannot name a directory, or cannot be addressed.
     *
     * <p>Whitespace, a path separator and {@code .}/{@code ..} are about the filesystem: §1.3
     * requires the directory to be named after the skill, and none of those can be part of one.
     *
     * <p>{@code @} is about the address instead. §4.1 spells a version as a suffix — {@code
     * ns/name@3} — so a name holding one would make the address undecidable: {@code name@3} could be
     * the third version of {@code name} or a skill literally called {@code name@3}. Reserving the
     * character is what keeps splitting at the first {@code @} unambiguous, and it forbids nothing a
     * reader would miss: §1.3's own rule for {@code name} is narrower still (lowercase letters,
     * digits and hyphens).
     *
     * <p>{@code %} and {@code ;} are about the transport, and they are the reason this check is
     * here rather than left to the URL builder. Both have to be percent-encoded into a path — {@code
     * %} as {@code %25}, {@code ;} as {@code %3B} — and Spring Security's {@code StrictHttpFirewall}
     * refuses both spellings outright, on the request URI and again on the decoded path. A name
     * holding one is therefore publishable and searchable but <em>unreachable</em>: the detail, the
     * body and every file 404 with a bare 400 before routing, and the delete endpoint fails the
     * same way, so the skill can never be removed either. Refusing it at the door is the only place
     * the two halves of that — what may be stored, and what a path can carry — can be kept in
     * agreement.
     */
    private static void requireUsableName(String name) {
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IngestException("the skill's name is longer than " + MAX_NAME_LENGTH + " characters",
                    "name", "name_too_long");
        }
        if (name.equals(".") || name.equals("..")
                || name.chars().anyMatch(c -> Character.isWhitespace(c) || c == '/' || c == '\\')) {
            throw new IngestException(
                    "the skill's name contains whitespace or a path separator, or is a relative path",
                    "name", "name_not_usable_as_a_directory");
        }
        if (name.indexOf('@') >= 0) {
            throw new IngestException(
                    "the skill's name contains '@', which separates a version in the skill's address",
                    "name", "name_contains_version_separator");
        }
        if (name.chars().anyMatch(c -> c == '%' || c == ';')) {
            throw new IngestException(
                    "the skill's name contains '%' or ';', which cannot be carried in a request path",
                    "name", "name_contains_unaddressable_char");
        }
    }
}
