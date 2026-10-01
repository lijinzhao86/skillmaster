package com.skillmasterai.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The blank test the three field policies share, and the character set it exists to state.
 *
 * <p>The set is Unicode's White_Space property, which is neither of Java's own tests — so the cases
 * below are the ones that tell the three apart, not a sample of spaces. Every one of them is a place
 * where a language's built-in would answer differently, and the browser mirror answers with this
 * property, so getting it from a built-in would mean the two halves of a form disagreeing about
 * whether something was filled in.
 */
class TextTest {

    /** White_Space, all twenty-five code points, as a list rather than as a loop over a range. */
    private static final List<String> WHITE_SPACE = List.of(
            "\t", "\n", "\u000B", "\f", "\r",           // 0009–000D
            " ",                                        // 0020
            "\u0085",                                   // 0085, next line — the one Java misses twice
            " ",                                   // 00A0, no-break space
            " ",                                   // 1680, ogham space mark
            " ", " ", " ", " ", " ", " ", " ", " ",
            " ", " ", " ",               // 2000–200A
            " ", " ",                         // line and paragraph separator
            " ",                                   // narrow no-break space
            " ",                                   // medium mathematical space
            "　");                                  // ideographic space, what a Chinese IME makes

    @Test
    void countsEveryCharacterInTheSetAsBlank() {
        assertThat(Text.isBlank(null)).isTrue();
        assertThat(Text.isBlank("")).isTrue();

        for (String character : WHITE_SPACE) {
            assertThat(Text.isBlank(character))
                    .as("U+%04X should be blank", character.codePointAt(0))
                    .isTrue();
            assertThat(Text.isBlank(character.repeat(8)))
                    .as("U+%04X repeated should be blank", character.codePointAt(0))
                    .isTrue();
        }
    }

    @Test
    void anythingWithACharacterInItIsNotBlank() {
        for (String character : WHITE_SPACE) {
            assertThat(Text.isBlank("a" + character))
                    .as("a value holding U+%04X is not blank", character.codePointAt(0))
                    .isFalse();
        }

        assertThat(Text.isBlank("密码")).isFalse();
    }

    @Test
    void leavesOutTheTwoCharactersARuntimeWouldHaveGotWrong() {
        // U+001C–U+001F are Java's isWhitespace, and they are file, group, record and unit
        // separators: they take no space and show nothing, so a value made of them is a value.
        assertThat(Character.isWhitespace(0x001C)).as("Java calls this whitespace").isTrue();
        assertThat(Text.isBlank("\u001C\u001C")).isFalse();

        // U+FEFF — a zero-width no-break space, which is to say a byte order mark — is whitespace
        // to JavaScript, so `trim()` removes it and a browser would call a value made of them
        // empty. It occupies no space, so it is not White_Space, and the mirror states this set
        // instead of calling `trim()` for exactly this reason. The other half of that claim is
        // pinned where it can be run: `tests/validation.test.ts` asserts the same value is not
        // blank to the mirror, while `trim()` still removes it.
        assertThat(Text.isBlank("\uFEFF")).isFalse();
    }
}
