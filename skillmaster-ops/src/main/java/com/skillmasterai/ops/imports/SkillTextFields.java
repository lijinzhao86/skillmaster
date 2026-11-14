package com.skillmasterai.ops.imports;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The text rules for a skill's author metadata, independent of how the frontmatter arrived.
 *
 * <p><strong>One contract, two doors.</strong> {@code SKILL.md} is parsed at write time and its
 * stored frontmatter is read again at publish time; a draft written under an older rule must not
 * bypass the current one. The two paths see different shapes (YAML at write, JSON at publish), so
 * the rule lives here over the parsed map rather than inside either parser.
 *
 * <p>Only {@code description} and the author's {@code when_to_use} are checked — {@code name} is
 * already fixed by the address at publish time and has its own usability rules at write time.
 */
public final class SkillTextFields {

    private SkillTextFields() {
    }

    /** One problem, with the sentence and the field-level detail the wire carries. */
    public record Problem(String message, ApiError.Detail detail) {
    }

    /**
     * @param whenToUse      the author's sentence, or null when absent or blank
     * @param whenToUseField where it came from — {@code metadata.when_to_use} or the top-level
     *                       fallback — so a refusal points at the field the author wrote
     * @param problems       every problem found, not just the first
     */
    public record Report(String description, String whenToUse, String whenToUseField,
            List<Problem> problems) {
        public Report {
            problems = List.copyOf(problems);
        }
    }

    /**
     * @param rawDescription the frontmatter value as parsed — not pre-narrowed to text, so a
     *                       non-text description is reported beside an over-long when_to_use in the
     *                       same pass rather than throwing before the second check
     */
    public static Report inspect(Object rawDescription, Map<String, Object> frontmatter) {
        List<Problem> problems = new ArrayList<>();
        String description = null;

        if (rawDescription == null
                || (rawDescription instanceof String text && text.isBlank())) {
            problems.add(new Problem("SKILL.md's frontmatter has no 'description'",
                    new ApiError.Detail("description", "required_field_missing")));
        } else if (!(rawDescription instanceof String text)) {
            problems.add(new Problem("SKILL.md's frontmatter field 'description' must be text,"
                    + " but is a " + rawDescription.getClass().getSimpleName(),
                    new ApiError.Detail("description", "field_not_text")));
        } else {
            description = text;
            int actual = SkillFieldLimits.codePoints(text);
            if (actual > SkillFieldLimits.DESCRIPTION_MAX_CODE_POINTS) {
                problems.add(new Problem(
                        "SKILL.md's description is " + actual + " code points; the limit is "
                                + SkillFieldLimits.DESCRIPTION_MAX_CODE_POINTS,
                        ApiError.Detail.codePoints("description", "too_long", actual,
                                SkillFieldLimits.DESCRIPTION_MAX_CODE_POINTS)));
            }
        }

        String field = "when_to_use";
        Object raw = null;
        Object metadata = frontmatter.get("metadata");
        Object nestedValue = metadata instanceof Map<?, ?> nested
                ? nested.get("when_to_use")
                : null;
        // A YAML key written with no value is null, and the projection SQL falls through it to
        // the top-level key. Validation has to follow the same rule, or the value SQL stores is
        // the one that was never checked.
        if (nestedValue != null) {
            field = "metadata.when_to_use";
            raw = nestedValue;
        } else if (frontmatter.get("when_to_use") != null) {
            raw = frontmatter.get("when_to_use");
        }

        String whenToUse = null;
        if (raw != null && !(raw instanceof String)) {
            problems.add(new Problem("SKILL.md's frontmatter field '" + field
                    + "' must be text, but is a " + raw.getClass().getSimpleName(),
                    new ApiError.Detail(field, "field_not_text")));
        } else if (raw instanceof String text && !text.isBlank()) {
            whenToUse = text;
            int actual = SkillFieldLimits.codePoints(text);
            if (actual > SkillFieldLimits.AUTHOR_WHEN_TO_USE_MAX_CODE_POINTS) {
                problems.add(new Problem(
                        "SKILL.md's " + field + " is " + actual + " code points; the limit is "
                                + SkillFieldLimits.AUTHOR_WHEN_TO_USE_MAX_CODE_POINTS,
                        ApiError.Detail.codePoints(field, "too_long", actual,
                                SkillFieldLimits.AUTHOR_WHEN_TO_USE_MAX_CODE_POINTS)));
            }
        }

        return new Report(description, whenToUse, field, problems);
    }

    /**
     * The spellings that look like the field but are not it.
     *
     * <p>They are warnings rather than refusals: the frontmatter is open to extensions, and a
     * client that stores its own {@code whenToUse} is not violating the format. But silently
     * treating it as "no sentence" would leave the author looking at a value the product ignores.
     */
    public static List<String> nearMissKeys(Map<String, Object> frontmatter) {
        List<String> found = new ArrayList<>();
        for (String key : List.of("whenToUse", "when-to-use", "whenToUseSentence")) {
            if (frontmatter.containsKey(key)) {
                found.add(key);
            }
        }
        Object metadata = frontmatter.get("metadata");
        if (metadata instanceof Map<?, ?> nested) {
            for (String key : List.of("whenToUse", "when-to-use", "whenToUseSentence")) {
                if (nested.containsKey(key)) {
                    found.add("metadata." + key);
                }
            }
        }
        return found;
    }
}
