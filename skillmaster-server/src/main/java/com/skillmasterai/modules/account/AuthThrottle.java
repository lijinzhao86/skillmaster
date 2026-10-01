package com.skillmasterai.modules.account;

/**
 * M1's rate limiter.
 *
 * <p>Its methods are named after what is being limited rather than taking a rule and a key. That is
 * the point: the keys are hashed values M1 derives, and an interface that accepted a key would be
 * one a caller could hand a raw phone number to — into the one table deliberately built to hold no
 * phone numbers.
 *
 * <p>What the rules are is below; what they cost is worth stating once. Sending an SMS spends money
 * per message, and a login endpoint without a counter answers password guesses as fast as the
 * network allows.
 */
public interface AuthThrottle {

    /**
     * Counts one verification-code send against the phone and against the caller.
     *
     * @param clientIp the caller's address, or null when it is not known
     * @return {@link Throttle.Refused} when this send would exceed a rule
     */
    Throttle countCodeSend(String phone, String clientIp);

    /**
     * Counts one login attempt against the phone.
     *
     * <p>Counted before the password is checked, so a phone that has spent its budget cannot log in
     * for the rest of the window even with the right password. That is the deliberate trade: the
     * alternative is to check the password first and count only failures, which lets an attacker
     * keep guessing for as long as the check takes. A successful sign-in clears the count, so what
     * accumulates is consecutive attempts rather than a lifetime.
     */
    Throttle countLoginAttempt(String phone);

    /**
     * Counts one **failed** login against the caller's address.
     *
     * <p>Failures only, and that is the difference from the rule above. An address is not a person:
     * a whole office or a carrier's NAT arrives from one, so an address budget that every attempt
     * spent would eventually refuse a sign-in whose password was right. Spending it only on failure
     * also means nobody can clear it — an attacker who owns one account must not be able to reset
     * the budget he is spending on other people's.
     *
     * @param clientIp the caller's address, or null when it is not known
     */
    Throttle recordLoginFailure(String clientIp);

    /** Forgets a phone's failed logins, so a legitimate login clears their own mistyping. */
    void clearLoginFailures(String phone);

    /**
     * Counts one captcha challenge issued to this caller.
     *
     * <p>Issuing one costs nothing and needs no account, so this is the only thing bounding how many
     * rows an anonymous caller can make this service draw and store. It is deliberately generous:
     * the challenge exists to slow down the endpoints that spend money, and a limit so tight that a
     * person who mistypes has to wait would be paid for by exactly the wrong party.
     *
     * @param clientIp the caller's address, or null when it is not known
     */
    Throttle countCaptchaIssue(String clientIp);
}
