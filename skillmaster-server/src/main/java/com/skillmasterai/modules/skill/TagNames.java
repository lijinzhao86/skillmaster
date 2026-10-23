package com.skillmasterai.modules.skill;

import java.text.Normalizer;
import java.util.Locale;

/**
 * How a tag word is spelled and how two spellings become one word (v14, ADR 0083).
 *
 * <p><strong>One implementation, on the server.</strong> The CLI sends the word a person typed and
 * the browser sends what it read; neither normalizes first, because a second copy of these rules is
 * a second answer waiting to disagree. The normalized key is what rows are stored and compared by;
 * the display form is kept beside it so a person still sees their own spelling.
 *
 * <p>The rules are deliberately plain — NFKC, trim, collapse internal whitespace, lower-case in
 * {@code Locale.ROOT} — because "PDF", "pdf" and a full-width "ＰＤＦ" are one word to a reader, and
 * anything cleverer (stemming, punctuation folding) would merge words a person meant to keep apart.
 */
public final class TagNames {

    /** Code points, not UTF-16 units: an emoji is one character to the person who typed it. */
    public static final int MAX_CODE_POINTS = 24;

    private TagNames() {
    }

    /**
     * The display form and the identity of one word.
     *
     * @param name what is shown and stored as the display spelling
     * @param key  what rows are unique by and lookups compare
     */
    public record Named(String name, String key) {
    }

    /**
     * Normalizes a submitted word.
     *
     * @throws SkillRequestException with field {@code name} and issue {@code invalid_name} when the
     *                               word is empty, contains {@code #}, or is longer than
     *                               {@link #MAX_CODE_POINTS}
     */
    public static Named parse(String raw) {
        String name = display(raw);
        // `#` is how a public tag is *written on screen*, never part of the word (voice-spec §公开
        // 标签写成 #名字). NFKC has already turned a full-width `＃` into `#`, so one check covers
        // both. Refusing it keeps the displayed form the stored form, which is what lets a name be
        // typed back in on any surface.
        if (name.isEmpty() || name.indexOf('#') >= 0
                || name.codePointCount(0, name.length()) > MAX_CODE_POINTS) {
            throw new SkillRequestException("name", "invalid_name");
        }
        return new Named(name, key(name));
    }

    /** The normalized identity of an already-normalized display form. */
    public static String key(String displayName) {
        return displayName.toLowerCase(Locale.ROOT);
    }

    /** NFKC, trimmed, internal whitespace collapsed. Case is preserved. */
    public static String display(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        return normalized.strip().replaceAll("[\\s\\p{Z}]+", " ");
    }
}
