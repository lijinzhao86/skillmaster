package com.skillmasterai.modules.ingest;

import java.util.Map;
import java.util.Optional;

/**
 * What a root {@code SKILL.md} has to say, and the one comparison the address makes with it.
 *
 * <p><strong>Extracted because two paths ask the same question.</strong> A submission parses the
 * file's frontmatter and checks {@code name} and {@code description}; a write of the root
 * {@code SKILL.md} (v7) has to apply the same rules to the same file, because the bytes are the same
 * kind of authoritative document either way. Two copies would eventually disagree about a blank
 * title or a nested mapping, and the disagreement would surface as a draft that can be written but
 * not published.
 *
 * <p>The rules are §1.3's: {@code name} and {@code description} are required, {@code title} falls
 * back to the name, and the name must be usable as a directory and as an address. This class does
 * not strip a shared root directory — that is a property of an archive, not of a document.
 */
public final class SkillDocument {

    private SkillDocument() {
    }

    /** Long enough for any real name, short enough to bound what a URL or an index has to carry. */
    private static final int MAX_NAME_LENGTH = 64;

    /**
     * @param name        the frontmatter {@code name}
     * @param title       the frontmatter {@code title}, or the name when absent or blank
     * @param description the frontmatter {@code description} — required
     * @param frontmatter every field as parsed, unknown ones included, stored verbatim
     */
    public record Document(String name, String title, String description,
            Map<String, Object> frontmatter) {
    }

    /**
     * @throws IngestException when the frontmatter is unreadable, a required field is missing, or
     *                         the name cannot be a directory or an address
     */
    public static Document parse(byte[] skillMd) {
        Map<String, Object> frontmatter = FrontmatterParser.parse(skillMd);
        String name = requiredText(frontmatter, "name");
        String description = requiredText(frontmatter, "description");
        // The blank filter is what makes `title: ""` fall back the way an absent title does: a
        // present-but-empty title would otherwise reach every search card as an empty line.
        String title = optionalText(frontmatter, "title").filter(value -> !value.isBlank())
                .orElse(name);
        requireUsableName(name);
        return new Document(name, title, description, frontmatter);
    }

    /**
     * The address and the content name one skill, or this is a different operation.
     *
     * <p>Checked where a version is added to a named skill: the address selects the row, the content
     * declares the name, and if they disagree the version would land on a skill its own frontmatter
     * does not describe. Nothing downstream would notice — every read resolves by the address.
     */
    public static void requireNameMatches(String addressName, Document document) {
        if (!addressName.equals(document.name())) {
            throw new IngestException("the address names '" + addressName + "' but SKILL.md"
                    + " declares '" + document.name() + "'; a version is added to the skill the"
                    + " address names, so the two have to be the same skill", "files[0].relpath",
                    "name_mismatch");
        }
    }

    private static String requiredText(Map<String, Object> frontmatter, String field) {
        return optionalText(frontmatter, field)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IngestException(
                        "SKILL.md's frontmatter has no '" + field + "'", field,
                        "required_field_missing"));
    }

    private static Optional<String> optionalText(Map<String, Object> frontmatter, String field) {
        Object value = frontmatter.get(field);
        // A nested mapping or a list where a string belongs is rejected rather than stringified:
        // String.valueOf on a Map produces something that looks like a value and is not one.
        if (value != null && !(value instanceof String)) {
            throw new IngestException(
                    "SKILL.md's frontmatter field '" + field + "' must be text, but is a "
                            + value.getClass().getSimpleName(),
                    field, "field_not_text");
        }
        return Optional.ofNullable((String) value);
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
     * character is what keeps splitting at the first {@code @} unambiguous.
     *
     * <p>{@code %} and {@code ;} are about the transport, and they are the reason this check is
     * here rather than left to the URL builder. Both have to be percent-encoded into a path — {@code
     * %} as {@code %25}, {@code ;} as {@code %3B} — and Spring Security's {@code StrictHttpFirewall}
     * refuses both spellings outright. A name holding one is publishable and searchable but
     * <em>unreachable</em>. Refusing it at the door is the only place the two halves — what may be
     * stored, and what a path can carry — can be kept in agreement.
     */
    private static void requireUsableName(String name) {
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IngestException(
                    "the skill's name is longer than " + MAX_NAME_LENGTH + " characters",
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
