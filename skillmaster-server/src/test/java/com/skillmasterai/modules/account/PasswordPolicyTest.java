package com.skillmasterai.modules.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What a password may be, at the boundaries where the rule is actually decided.
 *
 * <p>Unit tests rather than requests: the endpoint hashes with BCrypt at the configured cost, so
 * asserting the ceiling through it would pay a hash per case for a number that has nothing to do
 * with hashing. What the endpoint does with one of these refusals — which code reaches the client,
 * and under which field — is pinned in {@code WebRegistrationIT} and {@code WebPasswordResetIT}.
 *
 * <p>The ceiling itself is BCrypt's, and why it has to be enforced rather than assumed is pinned in
 * {@link BCryptPasswordHasherTest#ignoresEverythingPastTheSeventySecondByte}.
 */
class PasswordPolicyTest {

    private static final String HANDLE = "demo-user";
    private static final String PHONE = "13800138000";

    /**
     * A blocklist of the three entries these tests name, rather than the real 10001 — what is under
     * test here is the rule, not the data. That the real file loads, and how much of it is
     * reachable, is {@link PasswordBlocklistTest}'s job.
     */
    private static final PasswordBlocklist BLOCKLIST =
            new PasswordBlocklist(Set.of("password", "12345678", "qwerty123"));

    /**
     * The two numbers, as literals.
     *
     * <p>Every other test here reads the constants, so they move with them — a ceiling raised to
     * 200 would leave them all green while BCrypt went on truncating at 72, which is how two
     * different passwords come to open one account. This is the one place the numbers are written
     * out, and it is deliberate.
     */
    @Test
    void theTwoNumbersAreTheOnesBcryptAndTheProductDecided() {
        assertThat(PasswordPolicy.MIN_BYTES).isEqualTo(8);
        assertThat(PasswordPolicy.MAX_BYTES).isEqualTo(72);
    }

    @Test
    void acceptsTheShortestAndTheLongestItAllows() {
        assertThat(PasswordPolicy.problemWith("a".repeat(PasswordPolicy.MIN_BYTES), HANDLE, PHONE, BLOCKLIST))
                .isEmpty();
        assertThat(PasswordPolicy.problemWith("a".repeat(PasswordPolicy.MAX_BYTES), HANDLE, PHONE, BLOCKLIST))
                .isEmpty();
    }

    @Test
    void measuresInBytesThatForAcceptedPasswordsAreAlsoCharacters() {
        // The ceiling is BCrypt's and it is a byte count, so it stays expressed in bytes — but with
        // printable ASCII only, one byte is one character and the two counts can no longer disagree.
        // The byte semantics themselves live where they can still be observed, in
        // {@link BCryptPasswordHasherTest#ignoresEverythingPastTheSeventySecondByte}.
        //
        // This replaces a test that used 24 and 25 Chinese characters to tell the two apart. That
        // test is no longer meaningful here — and the reason is worth stating rather than deleting:
        // Chinese is now refused outright (ADR 0017), so those passwords never reach the length
        // rule, and `refusesChineseAndFullWidthCharacters` below is what pins that.
        String longest = "a".repeat(PasswordPolicy.MAX_BYTES);

        assertThat(longest.getBytes(StandardCharsets.UTF_8).length).isEqualTo(PasswordPolicy.MAX_BYTES);
        assertThat(PasswordPolicy.problemWith(longest, HANDLE, PHONE, BLOCKLIST)).isEmpty();
    }

    @Test
    void rejectsOneByteUnderTheFloorAndOneOverTheCeiling() {
        // The two off-by-one cases: a rule written with >= on one end or > on the other passes every
        // test above this line and fails a person at the form.
        assertThat(PasswordPolicy.problemWith("a".repeat(PasswordPolicy.MIN_BYTES - 1), HANDLE, PHONE, BLOCKLIST))
                .contains("too_short");
        assertThat(PasswordPolicy.problemWith("a".repeat(PasswordPolicy.MAX_BYTES + 1), HANDLE, PHONE, BLOCKLIST))
                .contains("too_long");
    }

    @Test
    void refusesTheUsernameAndThePhoneNumberThemselves() {
        assertThat(PasswordPolicy.problemWith(HANDLE, HANDLE, PHONE, BLOCKLIST)).contains("same_as_username");
        assertThat(PasswordPolicy.problemWith(PHONE, HANDLE, PHONE, BLOCKLIST)).contains("same_as_phone");
        // Equality, not containment: a password that merely contains the handle is exactly the long
        // passphrase the form recommends, and refusing those would be refusing the good answer.
        assertThat(PasswordPolicy.problemWith(HANDLE + "-and-then-some", HANDLE, PHONE, BLOCKLIST)).isEmpty();
    }

    @Test
    void answersTooShortBeforeItAnswersThatItMatchesTheHandle() {
        // The order is part of the contract, because the client shows one sentence and the server
        // picks which. The same value — a handle used as its own password — is refused for its
        // length when it is too short to be a password at all, and for matching when it is not:
        // told "same as your username" about a three-character one, its author would go and change
        // a different thing than the one being refused.
        String tooShortToBeAPassword = "abc";

        assertThat(PasswordPolicy.problemWith(tooShortToBeAPassword, tooShortToBeAPassword, PHONE, BLOCKLIST))
                .contains("too_short");
        assertThat(PasswordPolicy.problemWith(HANDLE, HANDLE, PHONE, BLOCKLIST)).contains("same_as_username");
    }

    @Test
    void reportsAMissingPasswordAsMissingRatherThanMalformed() {
        assertThat(PasswordPolicy.problemWith(null, HANDLE, PHONE, BLOCKLIST)).contains("required");
        assertThat(PasswordPolicy.problemWith("", HANDLE, PHONE, BLOCKLIST)).contains("required");
    }

    @Test
    void acceptsTheCharactersOnAKeyboardAndNothingElse() {
        // Printable ASCII, space included — a passphrase of words is the shape this policy wants.
        assertThat(PasswordPolicy.problemWith("correct horse battery", HANDLE, PHONE, BLOCKLIST))
                .isEmpty();
        assertThat(PasswordPolicy.problemWith("a!@#$%^&*()_+-=[]{};':\",./<>?|`~", HANDLE, PHONE, BLOCKLIST))
                .isEmpty();
        // And the two that are printable ASCII but invisible, which is why they are named: a tab
        // and a newline are not characters anyone can see to count.
        assertThat(PasswordPolicy.problemWith("tab\there!", HANDLE, PHONE, BLOCKLIST))
                .contains("invalid_format");
        assertThat(PasswordPolicy.problemWith("line\nbreak!", HANDLE, PHONE, BLOCKLIST))
                .contains("invalid_format");
    }

    @Test
    void refusesChineseAndFullWidthCharacters() {
        // The reason the rule exists (ADR 0017), and the case that made it necessary: a full-width
        // `ｐａｓｓｗｏｒｄ` is three bytes per character, walks straight past the blocklist, and is a
        // password its author cannot type again on a keyboard that is not in full-width mode.
        for (String refused : List.of("密码密码密码", "ｐａｓｓｗｏｒｄ", "ｐａｓｓｗｏｒｄ!", "pass word 密码",
                "🎵🎶 password")) {
            assertThat(PasswordPolicy.problemWith(refused, HANDLE, PHONE, BLOCKLIST))
                    .as("password %s", refused)
                    .contains("invalid_format");
        }
    }

    @Test
    void answersTheCharacterSetBeforeTheLength() {
        // Told "too short" about a Chinese password, its author types more Chinese. The two-character
        // case is short as well, and the set is still the answer that helps.
        assertThat(PasswordPolicy.problemWith("密码", HANDLE, PHONE, BLOCKLIST))
                .contains("invalid_format");
    }

    @Test
    void treatsAWhitespaceOnlyPasswordAsNothingTyped() {
        // Not "too short", and not accepted either: eight spaces is what a full-width input method
        // leaves behind, and the honest answer is that nothing was filled in.
        assertThat(PasswordPolicy.problemWith("        ", HANDLE, PHONE, BLOCKLIST))
                .contains("required");
        assertThat(PasswordPolicy.problemWith("　　　　", HANDLE, PHONE, BLOCKLIST))
                .contains("required");
    }

    @Test
    void refusesAPasswordFromTheBlocklist() {
        // What replaced the character-class rules (ADR 0016): `password` and `12345678` satisfy
        // "eight characters with a number in it", and are the first two things anybody tries.
        assertThat(PasswordPolicy.problemWith("password", HANDLE, PHONE, BLOCKLIST))
                .contains("too_common");
        assertThat(PasswordPolicy.problemWith("12345678", HANDLE, PHONE, BLOCKLIST))
                .contains("too_common");
        assertThat(PasswordPolicy.problemWith("PassWord", HANDLE, PHONE, BLOCKLIST))
                .contains("too_common");
    }

    @Test
    void leavesALongPassphraseAloneEvenWhenItContainsABlockedWord() {
        // Equality against the list, not containment. A rule that refused everything holding
        // "password" would refuse the passphrase the form recommends, which is a worse trade than
        // the gap it closes.
        assertThat(PasswordPolicy.problemWith("password-and-then-some-more", HANDLE, PHONE, BLOCKLIST))
                .isEmpty();
    }

    @Test
    void refusesTheHandleWithSomethingStuckOnTheEnd() {
        // Equality alone misses this, and this is what a hurried person actually types.
        assertThat(PasswordPolicy.problemWith("demo-user123", HANDLE, PHONE, BLOCKLIST))
                .contains("too_common");
        assertThat(PasswordPolicy.problemWith("demouser1", HANDLE, PHONE, BLOCKLIST))
                .contains("too_common");
        // Punctuation in the middle changes nothing about what it is.
        assertThat(PasswordPolicy.problemWith("demo.user.2026", HANDLE, PHONE, BLOCKLIST))
                .contains("too_common");
    }

    @Test
    void refusesTheHandleWithDigitsOnTheEndEvenWhenTheHandleEndsInDigits() {
        // The case a hand-picked handle hides, and the one an integration test with a random handle
        // found: `u3f0a` + `123`. A rule that stripped the password's trailing digits and compared
        // would eat the handle's own last digit and let this through.
        assertThat(PasswordPolicy.problemWith("u3f0a123", "u3f0a", PHONE, BLOCKLIST))
                .contains("too_common");
        // And case and punctuation do not change what it is: this reaches this rule only because
        // the equality check above is case-sensitive.
        assertThat(PasswordPolicy.problemWith("DEMOUSER", HANDLE, PHONE, BLOCKLIST))
                .contains("too_common");
        assertThat(PasswordPolicy.problemWith("Demo-User!", HANDLE, PHONE, BLOCKLIST))
                .contains("too_common");
    }

    @Test
    void refusesThePhoneNumberWhereverItSits() {
        assertThat(PasswordPolicy.problemWith("a13800138000", HANDLE, PHONE, BLOCKLIST))
                .contains("too_common");
        assertThat(PasswordPolicy.problemWith("13800138000a", HANDLE, PHONE, BLOCKLIST))
                .contains("too_common");
        assertThat(PasswordPolicy.problemWith("13800138000!", HANDLE, PHONE, BLOCKLIST))
                .contains("too_common");
    }

    @Test
    void doesNotRefuseALongPassphraseForMerelyHoldingTheHandle() {
        // The boundary of the rule above, and the reason it strips a trailing run of digits instead
        // of testing containment: eleven digits in a row cannot be a coincidence, but a three-letter
        // handle sits inside ordinary words, and these two are good passwords.
        assertThat(PasswordPolicy.problemWith("demo-user-and-then-some", HANDLE, PHONE, BLOCKLIST))
                .isEmpty();
        assertThat(PasswordPolicy.problemWith("my-demo-user-passphrase", HANDLE, PHONE, BLOCKLIST))
                .isEmpty();
    }
}
