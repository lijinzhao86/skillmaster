package com.skillmasterai.modules.account;

/**
 * The SMS code was not accepted — wrong, expired, already used, guessed at too many times, or never
 * issued at all.
 *
 * <p>One type and one wire code for all five. Which one it was is exactly what an attacker would
 * use to decide whether to keep trying, and the legitimate caller does the same thing in every
 * case: ask for a new code.
 *
 * <p><strong>{@link #detail} is for the log and never for the response.</strong> The advice that
 * turns this into an error body writes a constant message, so the reason a code failed cannot leak
 * back through the one field the message otherwise carries.
 */
public final class VerificationCodeException extends RuntimeException {

    private final String detail;

    public VerificationCodeException(String detail) {
        super("the verification code was not accepted: " + detail);
        this.detail = detail;
    }

    /** Why it failed, for the log only. */
    public String detail() {
        return detail;
    }
}
