package com.skillmasterai.ops.imports;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Parses the YAML frontmatter block of a {@code SKILL.md}.
 *
 * <p>Written against a real YAML parser, which is the whole point. The archived baseline had a
 * hand-rolled one whose four defects (known-issues.md M5, all reproduced) were: inline comments
 * taken as part of the value, block scalars {@code |} and {@code >} reduced to a literal
 * character, nested mappings such as {@code metadata.requires.bins} silently emptied, and a
 * UTF-8 BOM causing the entire frontmatter to be discarded without a warning.
 *
 * <p>The rule that matters here is §3.3's: <strong>a parse failure must not degrade silently.</strong>
 * Anything this class cannot understand raises {@link IngestException} rather than returning a
 * partial map. There is no "best effort" path, because a partially-parsed skill is published
 * looking complete.
 *
 * <p>Only the frontmatter is returned. The body is not: the stored artefact is the uploaded bytes
 * themselves (ADR 0005 forbids rewriting them), so nothing downstream needs a re-serialised copy —
 * and reconstructing one would have to guess at line terminators.
 */
public final class FrontmatterParser {

    private FrontmatterParser() {
    }

    private static final String DELIMITER = "---";
    private static final char BOM = '\uFEFF';  // escaped on purpose: an invisible
    // character in source is unreviewable

    /** Frontmatter is small; the cap is only here so a pathological file cannot be expensive. */
    private static final int MAX_FRONTMATTER_CHARS = 1_000_000;

    private static final int MAX_YAML_ALIASES = 10;

    /**
     * Deep enough for any real frontmatter, shallow enough to stop a cycle before it becomes one.
     *
     * <p>A YAML alias may point at its own ancestor — {@code a: &x {self: *x}} is two lines and
     * SnakeYAML builds that object graph without complaint. Nothing here recurses, so it used to
     * reach Jackson, which refuses a structure that deep and threw — answering 500 for what is
     * plainly a bad upload, on the one endpoint a client expects a 400 from.
     */
    private static final int MAX_FRONTMATTER_DEPTH = 32;

    public static Map<String, Object> parse(byte[] skillMd) {
        String text = decode(skillMd);

        if (!text.isEmpty() && text.charAt(0) == BOM) {
            // Tolerated for parsing only. The stored bytes keep their BOM, so the digest still
            // describes exactly what was uploaded; what changes is that the frontmatter is found
            // instead of silently ignored.
            text = text.substring(1);
        }

        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().trim().equals(DELIMITER)) {
            throw new IngestException(
                    "SKILL.md must open with a YAML frontmatter block delimited by '---'",
                    "SKILL.md", "missing_frontmatter");
        }

        int end = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(DELIMITER)) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IngestException(
                    "SKILL.md's frontmatter block is never closed by a '---' line",
                    "SKILL.md", "unterminated_frontmatter");
        }

        String block = String.join("\n", lines.subList(1, end));
        if (block.length() > MAX_FRONTMATTER_CHARS) {
            throw new IngestException("SKILL.md's frontmatter is unreasonably large",
                    "SKILL.md", "frontmatter_too_large");
        }
        if (block.isBlank()) {
            throw new IngestException("SKILL.md has an empty frontmatter block",
                    "SKILL.md", "empty_frontmatter");
        }

        Object parsed = load(block);
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IngestException(
                    "SKILL.md's frontmatter must be a YAML mapping of fields",
                    "SKILL.md", "frontmatter_not_a_mapping");
        }
        requireBoundedDepth(parsed, 1);

        Map<String, Object> fields = new LinkedHashMap<>();
        raw.forEach((key, value) -> fields.put(String.valueOf(key), value));
        return fields;
    }

    /**
     * Refuses a value with no bottom, before anything tries to walk it.
     *
     * <p>Both a cycle and a genuinely absurd nesting reach the same limit, which is why one check
     * covers both: a self-referential node recurses forever and a deep one runs out of levels. The
     * cost of the limit being generous is nothing — frontmatter is a mapping of scalars with the
     * occasional nested {@code metadata}.
     */
    private static void requireBoundedDepth(Object value, int depth) {
        if (depth > MAX_FRONTMATTER_DEPTH) {
            throw new IngestException(
                    "SKILL.md's frontmatter nests more than " + MAX_FRONTMATTER_DEPTH
                            + " levels, or a node refers to an ancestor of itself",
                    "SKILL.md", "frontmatter_too_deep");
        }
        if (value instanceof Map<?, ?> mapping) {
            mapping.forEach((key, nested) -> {
                requireBoundedDepth(key, depth + 1);
                requireBoundedDepth(nested, depth + 1);
            });
        } else if (value instanceof Iterable<?> items) {
            items.forEach(item -> requireBoundedDepth(item, depth + 1));
        }
    }

    private static Object load(String block) {
        LoaderOptions options = new LoaderOptions();
        // Duplicate keys are rejected rather than last-one-wins: the two values are equally
        // plausible readings of the author's intent, so picking one silently is a guess.
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(MAX_YAML_ALIASES);
        options.setCodePointLimit(MAX_FRONTMATTER_CHARS);
        try {
            return new Yaml(new SafeConstructor(options)).load(block);
        } catch (YAMLException e) {
            throw new IngestException("SKILL.md's frontmatter is not valid YAML: " + e.getMessage(),
                    "SKILL.md", "invalid_yaml");
        }
    }

    private static String decode(byte[] skillMd) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(skillMd))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IngestException("SKILL.md is not valid UTF-8", "SKILL.md", "not_utf8");
        }
    }
}
