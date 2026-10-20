package com.skillmasterai.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The grammar of a row id: eight Crockford characters, and nothing more lenient than that. */
class ShortCodeTest {

    @Test
    void everyGeneratedCodeIsEightCharactersOfTheAlphabet() {
        for (int i = 0; i < 1000; i++) {
            String code = ShortCode.generate();
            assertThat(code).hasSize(8);
            assertThat(ShortCode.isValid(code)).isTrue();
            // Crockford drops I, L, O and U so a code cannot be misread as a word or as another code.
            assertThat(code).doesNotContain("I").doesNotContain("L")
                    .doesNotContain("O").doesNotContain("U");
        }
    }

    @Test
    void validationRejectsLengthCaseAndAliases() {
        assertThat(ShortCode.isValid("01234567")).isTrue();
        assertThat(ShortCode.isValid(null)).isFalse();
        assertThat(ShortCode.isValid("0123456")).as("too short").isFalse();
        assertThat(ShortCode.isValid("012345678")).as("too long").isFalse();
        assertThat(ShortCode.isValid("abcdefgh")).as("lowercase is rejected, not folded").isFalse();
        assertThat(ShortCode.isValid("0123456I")).as("I is not in the alphabet").isFalse();
        assertThat(ShortCode.isValid("0123456L")).as("L is not in the alphabet").isFalse();
        assertThat(ShortCode.isValid("0123456O")).as("O is not in the alphabet").isFalse();
        assertThat(ShortCode.isValid("0123456U")).as("U is not in the alphabet").isFalse();
    }

    @Test
    void aRowIdIsAShortCodeOrALegacyUlid() {
        assertThat(ShortCode.isValidRowId(ShortCode.generate())).isTrue();
        assertThat(ShortCode.isValidRowId(Ulid.generate())).as("a row written before v7").isTrue();
        assertThat(ShortCode.isValidRowId("0123456")).isFalse();
        assertThat(ShortCode.isValidRowId("draft:01234567")).isFalse();
    }
}
