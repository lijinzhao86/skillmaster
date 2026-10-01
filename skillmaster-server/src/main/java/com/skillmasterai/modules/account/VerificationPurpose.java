package com.skillmasterai.modules.account;

/**
 * Which flow an SMS code was issued for.
 *
 * <p>Part of the code's identity rather than a label on it: a code sent to register an account must
 * not be accepted to reset a password, or the weaker of the two flows becomes a way into the
 * stronger one. The stored column is what keeps them apart.
 */
public enum VerificationPurpose {

    REGISTER("register"),
    RESET("reset");

    private final String wireName;

    VerificationPurpose(String wireName) {
        this.wireName = wireName;
    }

    /** The value stored in {@code phone_verification.purpose}. */
    public String wireName() {
        return wireName;
    }
}
