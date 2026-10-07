package com.skillmasterai.modules.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class UsernamePolicyTest {

    @Test
    void acceptsTheShapesAnAddressCanCarry() {
        assertThat(UsernamePolicy.problemWith("abcdef")).isEmpty();
        assertThat(UsernamePolicy.problemWith("a1b2c3")).isEmpty();
        assertThat(UsernamePolicy.problemWith("123456")).isEmpty();
        assertThat(UsernamePolicy.problemWith("abc-def-123")).isEmpty();
        assertThat(UsernamePolicy.problemWith("a".repeat(UsernamePolicy.MAX_LENGTH))).isEmpty();
    }

    @Test
    void acceptsATrailingOrDoubledHyphen() {
        // The pattern permits both. Pinned so that it stays a decision: tightening this later is
        // safe, loosening it is not, and only an explicit expectation tells the two apart from a
        // bug in whatever the pattern happens to be today.
        assertThat(UsernamePolicy.problemWith("abcde-")).isEmpty();
        assertThat(UsernamePolicy.problemWith("ab--cd")).isEmpty();
    }

    @Test
    void rejectsByLength() {
        // The floor, at its boundary: five is refused and six is not, which is what makes the number
        // a stated rule rather than a number.
        assertThat(UsernamePolicy.problemWith("abcde")).contains("invalid_length");
        assertThat(UsernamePolicy.problemWith("abcdef")).isEmpty();
        assertThat(UsernamePolicy.problemWith("a".repeat(UsernamePolicy.MAX_LENGTH + 1)))
                .contains("invalid_length");
    }

    @Test
    void rejectsWhatAnAddressCannotCarry() {
        // Every one of these would end up percent-encoded in a published address, or refused by the
        // request path outright. The username is namespace slug and first path segment, so this is
        // not tidiness — see M01's 不变量 and the v1-hosting test plan's known issues.
        //
        // Long enough to clear the floor on purpose: the alphabet is judged first, and a case that
        // failed both would pass this test for the wrong reason.
        for (String handle : List.of("Abcdef", "ABCDEF", "飞书用户名", "abcde_c", "abcde.c",
                "abcde@c", "abcde%c", "abcde;c", "abcde c", "-abcdef", "abcde/c")) {
            assertThat(UsernamePolicy.problemWith(handle))
                    .as("username %s", handle)
                    .contains("invalid_format");
        }
    }

    @Test
    void reportsAMissingUsernameAsMissingRatherThanMalformed() {
        // Different issues because they are different things to the client: one is "fill this in",
        // the other is "this is not a username". A single format error for both teaches nothing.
        assertThat(UsernamePolicy.problemWith(null)).contains("required");
        assertThat(UsernamePolicy.problemWith("")).contains("required");
        assertThat(UsernamePolicy.problemWith("   ")).contains("required");
    }
}
