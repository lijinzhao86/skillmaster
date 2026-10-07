package com.skillmasterai.modules.version.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Caller text becoming a {@code LIKE} pattern that matches it literally.
 *
 * <p>Asserted by value rather than by "it did not blow up", because a wrong escape produces
 * plausible results instead of an error: the query succeeds and returns rows that do not contain
 * what was searched for. Nothing downstream can notice.
 */
class LikePatternTest {

    @Test
    void matchesTheTextLiterally() {
        assertThat(LikePattern.containing("飞书")).isEqualTo("%飞书%");
    }

    @Test
    void escapesWhatLikeWouldOtherwiseReadAsAPattern() {
        // `%` and `_` are the two characters that mean something to LIKE. Here they mean themselves:
        // a search for `_` is a search for that character, not for any single character.
        assertThat(LikePattern.containing("%")).isEqualTo("%\\%%");
        assertThat(LikePattern.containing("a_b")).isEqualTo("%a\\_b%");
        assertThat(LikePattern.containing("100%")).isEqualTo("%100\\%%");
    }

    @Test
    void escapesTheEscapeCharacterItself() {
        // The case nothing covered. `\` is what quotes the other two, so it has to be quoted too:
        // undoubled, a search for `\d` reaches LIKE as a literal `d` and quietly returns the wrong
        // rows. This character is also the one a query is least likely to contain, which is why it
        // is the one nobody would notice was missing.
        assertThat(LikePattern.containing("\\")).isEqualTo("%\\\\%");
        assertThat(LikePattern.containing("a\\b")).isEqualTo("%a\\\\b%");
        assertThat(LikePattern.containing("\\%")).isEqualTo("%\\\\\\%%");
    }

    @Test
    void hasNoPatternWhenThereIsNoText() {
        // null rather than "%%": the caller uses this to decide whether to filter at all, and "%%"
        // would match every row while looking like a filter that matched them.
        assertThat(LikePattern.containing(null)).isNull();
        assertThat(LikePattern.containing("")).isNull();
    }

    @Test
    void theEscapeClauseNamesTheSameCharacterThePatternsQuote() {
        // The pattern and the ESCAPE clause are one idea in two places, which is why they live in
        // one class. If they ever disagree, every escaped character silently stops being escaped.
        assertThat(LikePattern.ESCAPE_CLAUSE).isEqualTo("ESCAPE '\\'");
    }
}
