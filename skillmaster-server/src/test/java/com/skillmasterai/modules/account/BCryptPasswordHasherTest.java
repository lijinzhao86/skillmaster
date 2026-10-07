package com.skillmasterai.modules.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the two properties the rest of the design leans on: the stored value is not the password,
 * and BCrypt's 72-byte ceiling is real.
 */
class BCryptPasswordHasherTest {

    /**
     * The cheapest legal cost. Not a shortcut around the property under test — the production
     * default is 10 — but this class is instantiated once per test method and a suite that hashes
     * hundreds of passwords does not need to spend seconds proving that hashing works.
     */
    private static final int TEST_STRENGTH = 4;

    private final BCryptPasswordHasher hasher = new BCryptPasswordHasher(TEST_STRENGTH);

    @Test
    void storesSomethingOtherThanThePassword() {
        String hash = hasher.hash("correct horse battery staple");

        assertThat(hash).doesNotContain("correct horse battery staple");
        assertThat(hash).startsWith("$2");
    }

    @Test
    void acceptsTheRightPasswordAndRefusesEverythingElse() {
        String hash = hasher.hash("correct horse battery staple");

        assertThat(hasher.matches("correct horse battery staple", hash)).isTrue();
        assertThat(hasher.matches("correct horse battery stapl", hash)).isFalse();
        assertThat(hasher.matches("", hash)).isFalse();
    }

    @Test
    void saltsSoTwoHashesOfOnePasswordDiffer() {
        // Both must still verify. If they did not, this would be a test that the salt broke login.
        String first = hasher.hash("same password");
        String second = hasher.hash("same password");

        assertThat(first).isNotEqualTo(second);
        assertThat(hasher.matches("same password", first)).isTrue();
        assertThat(hasher.matches("same password", second)).isTrue();
    }

    @Test
    void ignoresEverythingPastTheSeventySecondByte() {
        // This is BCrypt's ceiling, not ours, and it is why the password policy caps length at 72
        // bytes: unhandled, two different long passwords authenticate the same account, and the
        // person who chose the longer one has no way to find out. Pinned here because the policy's
        // cap is otherwise an unexplained number.
        String seventyTwo = "a".repeat(72);
        String seventyThree = seventyTwo + "b";

        assertThat(seventyThree).hasSize(73);
        assertThat(hasher.matches(seventyThree, hasher.hash(seventyTwo)))
                .as("the 73rd byte cannot change the hash, so it cannot change the outcome")
                .isTrue();
    }

    @Test
    void spendsAComparisonForWhateverTheCallerSent() {
        // The branch for "no account with this phone" runs this so that branch costs what a real
        // comparison costs; an endpoint that answers faster for unregistered numbers is an
        // account-existence oracle that identical response bodies do not close. That *cost* is a
        // duration, and asserting a duration would be flaky — so what is pinned here is the other
        // half, which is a bug waiting to happen: the input is whatever the caller sent, and a
        // throw on any of these would turn "no such account" into a 500. The 73-byte case is the
        // real one — an implementation that reached for `hash` instead of a comparison throws
        // exactly there, and this goes red.
        for (String input : List.of("", "anything at all", "a".repeat(72), "a".repeat(73), "密码")) {
            hasher.spendComparison(input);
        }
    }
}
