package com.skillmasterai.common;

/**
 * The three text limits of a skill, in Unicode code points (ADR 0181).
 *
 * <p><strong>One source for parse, publish and the personal API.</strong> The author's fields are
 * checked when {@code SKILL.md} is written and again when a version is published; the personal
 * sentence is checked by its own write. If those three places counted differently, the same value
 * could pass one door and fail another.
 *
 * <p>Unit is the code point, not the UTF-8 byte and not the UTF-16 unit: a han character, a Latin
 * letter and a punctuation mark each count as one, and an emoji is one rather than two.
 */
public final class SkillFieldLimits {

    private SkillFieldLimits() {
    }

    /** {@code SKILL.md}'s {@code description}: required, so the floor is one. */
    public static final int DESCRIPTION_MIN_CODE_POINTS = 1;

    /** The specification's compatibility ceiling for {@code description}. */
    public static final int DESCRIPTION_MAX_CODE_POINTS = 1024;

    /** The author's {@code metadata.when_to_use}: optional; absent means no sentence. */
    public static final int AUTHOR_WHEN_TO_USE_MAX_CODE_POINTS = 200;

    /** The caller's own sentence; 1–200, and an empty value is a clear rather than a write. */
    public static final int MY_WHEN_TO_USE_MAX_CODE_POINTS = 200;

    public static int codePoints(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }
}
