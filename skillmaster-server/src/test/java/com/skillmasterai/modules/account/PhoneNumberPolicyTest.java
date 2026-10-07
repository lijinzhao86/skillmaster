package com.skillmasterai.modules.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a phone number may be, and what it deliberately may not.
 *
 * <p>The rule is narrow on purpose: a number accepted here gets stored and then has to receive a
 * text message, so anything a provider would refuse turns "you typed it wrong" into "the code never
 * arrived" — for a person who can see neither the column nor the provider's error.
 *
 * <p>This is the rule the browser client mirrors in {@code skillmaster-web/src/validation.ts}, which
 * is where the two are allowed to disagree only if the client is the stricter of the two.
 */
class PhoneNumberPolicyTest {

    @Test
    void acceptsTheElevenDigitsAMainlandNumberHas() {
        assertThat(PhoneNumberPolicy.problemWith("13000000000")).isEmpty();
        assertThat(PhoneNumberPolicy.problemWith("13800138000")).isEmpty();
        assertThat(PhoneNumberPolicy.problemWith("19912345678")).isEmpty();
    }

    @Test
    void rejectsASecondDigitOutsideThreeToNine() {
        // 10, 11 and 12 are not allocated to mobiles. The middle case is the one a real typo
        // produces: 13 typed as 12.
        assertThat(PhoneNumberPolicy.problemWith("10800138000")).contains("invalid_format");
        assertThat(PhoneNumberPolicy.problemWith("11800138000")).contains("invalid_format");
        assertThat(PhoneNumberPolicy.problemWith("12800138000")).contains("invalid_format");
    }

    @Test
    void rejectsTheWrongNumberOfDigits() {
        assertThat(PhoneNumberPolicy.problemWith("1380013800")).contains("invalid_format");
        assertThat(PhoneNumberPolicy.problemWith("13800138000" + "0")).contains("invalid_format");
    }

    @Test
    void rejectsWhatIsNotElevenDigits() {
        // The first three are what people type into a form with no country code, and the last one is
        // full-width: Java's \d is ASCII-only, so those digits are not digits here. Pinned rather
        // than left to whatever the pattern happens to do with them.
        for (String notANumber : List.of("8613800138000", "+8613800138000", "138 0013 8000",
                "138-0013-8000", "1380013800a", "１３８００１３８０００")) {
            assertThat(PhoneNumberPolicy.problemWith(notANumber))
                    .as("phone %s", notANumber)
                    .contains("invalid_format");
        }
    }

    @Test
    void reportsAMissingNumberAsMissingRatherThanMalformed() {
        // "Fill this in" and "this is not a number" are different instructions to the person typing.
        assertThat(PhoneNumberPolicy.problemWith(null)).contains("required");
        assertThat(PhoneNumberPolicy.problemWith("")).contains("required");
        assertThat(PhoneNumberPolicy.problemWith("   ")).contains("required");
    }
}
