package com.skillmasterai.modules.account;

import com.skillmasterai.common.Text;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * What a password is allowed to be.
 *
 * <p>Three things: printable ASCII only, a length between {@value #MIN_BYTES} and
 * {@value #MAX_BYTES}, and not one of the passwords that are already known to be worthless.
 * <strong>No character-class requirements</strong> — nothing has to contain a digit or a symbol,
 * because those rules push people towards {@code Password1!} and away from anything long. What
 * replaces them is the blocklist (see {@link PasswordBlocklist} and ADR 0016).
 *
 * <p><strong>Printable ASCII only is a restriction, and it is a deliberate one</strong> (ADR 0017).
 * It costs the Chinese passphrase, which the standards would have us accept, and it buys the
 * disappearance of a whole class of failures: the visible text of a password stops having more than
 * one encoding. A full-width {@code ｐａｓｓｗｏｒｄ} looks like {@code password} and is three bytes per
 * character — enough to walk past the blocklist, and enough for a person to set a password they
 * cannot type again. Refusing it at the form says so at the one moment it can be said.
 *
 * <p><strong>The 72-byte ceiling is BCrypt's, not ours.</strong> The algorithm ignores everything
 * past that byte, so without a cap two different long passwords authenticate the same account and
 * the person who chose the longer one has no way to find out. Capping at the limit is the honest
 * fix; truncating silently is not. It is still counted in bytes rather than characters — with ASCII
 * only, the two counts agree for everything that can be accepted, and the check stays expressed in
 * the unit BCrypt actually uses.
 *
 * <p><strong>Order is part of the contract.</strong> Each check answers the question the person in
 * front of the form can act on: a short password is told it is short even when it is also the
 * handle, because "that is your username" sends its author off to change something that was not the
 * problem. The character set comes before the length for the same reason — told "too short" about a
 * Chinese password, its author would type more Chinese.
 */
public final class PasswordPolicy {

    public static final int MIN_BYTES = 8;
    public static final int MAX_BYTES = 72;

    /** What a password built out of the handle is refused with; the length rules cannot see it. */
    private static final String TOO_COMMON = "too_common";

    private PasswordPolicy() {
    }

    /**
     * @param rawPassword the password as given, possibly null
     * @param handle      the username being registered or the account's existing one
     * @param phone       the phone number, so that using it as a password is refused
     * @param blocklist   the passwords everybody picks, which nothing but data can tell us
     * @return an issue code naming what is wrong, or empty when the password is acceptable
     */
    public static Optional<String> problemWith(String rawPassword, String handle, String phone,
            PasswordBlocklist blocklist) {
        // isBlank, not isEmpty: eight spaces is not a password, and it is exactly the shape a
        // full-width input method leaves behind. Treating it as "nothing typed" is both true and the
        // message the person can act on.
        if (Text.isBlank(rawPassword)) {
            return Optional.of("required");
        }
        if (!isPrintableAscii(rawPassword)) {
            return Optional.of("invalid_format");
        }
        int bytes = rawPassword.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_BYTES) {
            return Optional.of("too_short");
        }
        if (bytes > MAX_BYTES) {
            return Optional.of("too_long");
        }
        // Not security theatre: these two are seconds of work for an attacker who has read the
        // request, and they are the two a hurried person actually types.
        if (rawPassword.equals(handle)) {
            return Optional.of("same_as_username");
        }
        if (rawPassword.equals(phone)) {
            return Optional.of("same_as_phone");
        }
        if (blocklist.contains(rawPassword)) {
            return Optional.of(TOO_COMMON);
        }
        if (handleWithDecoration(rawPassword, handle) || containsPhoneNumber(rawPassword, phone)) {
            return Optional.of(TOO_COMMON);
        }
        return Optional.empty();
    }

    /**
     * The handle with the decoration people add to get past a length rule: {@code demo-user123}.
     *
     * <p>Equality alone leaves these open, and they are what a hurried person actually types.
     * Deliberately not "contains the handle", which would refuse {@code demo-user-and-then-some}:
     * the long passphrase is the answer this whole policy is trying to encourage, and a rule that
     * refuses it is worse than the gap it closes.
     *
     * <p>The test is "the handle, then nothing but digits", not "strip the password's trailing
     * digits and compare". Those differ exactly when the handle itself ends in a digit —
     * {@code u3f0a} with {@code u3f0a123} — because stripping eats the handle's own last digit and
     * misses every such case. An integration test with a random handle found that; a hand-picked one
     * would not have.
     */
    private static boolean handleWithDecoration(String password, String handle) {
        if (Text.isBlank(handle)) {
            return false;
        }
        String squashedHandle = squashed(handle);
        String core = squashed(password);
        if (squashedHandle.isEmpty() || !core.startsWith(squashedHandle)) {
            return false;
        }
        // Nothing left at all also counts: `DEMO-USER` is the handle too, and it reaches here only
        // because the equality check above is case-sensitive.
        return core.substring(squashedHandle.length()).chars().allMatch(Character::isDigit);
    }

    /**
     * The phone number, with anything around it: {@code 13800138000}, {@code a13800138000}.
     *
     * <p>Containment is safe for the number in a way it is not for a handle: eleven digits in a row
     * cannot be a coincidence, while a three-letter handle appears inside ordinary words.
     */
    private static boolean containsPhoneNumber(String password, String phone) {
        return !Text.isBlank(phone) && squashed(password).contains(squashed(phone));
    }

    /** What is left of a value once case and punctuation stop distinguishing it. */
    private static String squashed(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Everything from the space to the tilde, and nothing else.
     *
     * <p>A space <em>inside</em> a password is allowed — it is printable ASCII, and a passphrase of
     * words is the shape this policy is trying to encourage. Tab and newline are not, and neither is
     * anything above U+007E: what a user searching this rule wants to know is that the characters
     * they can see on a US keyboard are the characters the password is made of.
     */
    private static boolean isPrintableAscii(String value) {
        return value.chars().allMatch(c -> c >= 0x20 && c <= 0x7e);
    }
}
