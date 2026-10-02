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
     * Claims the one verification-code send this caller's address gets without solving a captcha.
     *
     * <p>A claim rather than a count: what matters is whether the allowance has been taken, not how
     * often it was asked for, so the answer is a boolean and the row's existence is the state. Two
     * callers arriving together cannot both be told yes — the insert and the answer are one
     * statement, the same way the counters above are.
     *
     * <p>This is <em>not</em> a limit on sending. It decides what the send costs the caller, and
     * {@link #countCodeSend} is still what decides whether there is a send at all; a caller whose
     * claim succeeds and who is then refused there has spent nothing, because the refusal rolls the
     * claim back with the rest of the transaction.
     *
     * @param clientIp the caller's address, or null when it is not known
     * @return whether the claim was granted. Null is a refusal, deliberately: a send that cannot be
     *         attributed to an address is one that has to pay, so the unknown case fails closed
     *         rather than handing out an allowance nobody is accountable for
     */
    boolean claimFreeCodeSend(String clientIp);

    /**
     * Whether this caller's address still has its one captcha-free send.
     *
     * <p>The fact {@link #claimFreeCodeSend} writes, read instead of taken. It exists so a form can
     * draw the captcha before it is asked for rather than after somebody has pressed a button and been
     * refused.
     *
     * <p>Advice, never a promise: the answer can be stale by the time the send it was asked about is
     * made — the same address sending from a second tab, or the window rolling over between the two.
     * So the refusal path has to stay, and this only spares the caller a round trip through it.
     *
     * @param clientIp the caller's address, or null when it is not known
     * @return whether a send from here would go out without a captcha
     */
    boolean freeCodeSendAvailable(String clientIp);

    /**
     * Counts one captcha-free-allowance lookup against the caller's address.
     *
     * <p>For the same reason as {@link #countUsernameLookup}: a free anonymous endpoint that answers a
     * question is a load vector whatever it answers. Generous, because a form asks once as it opens.
     *
     * @param clientIp the caller's address, or null when it is not known
     */
    Throttle countCodePolicyRead(String clientIp);

    /**
     * Counts one username-availability lookup against the caller's address.
     *
     * <p>The one rule here that guards nothing that costs money. What it bounds is a free anonymous
     * endpoint that answers a question about which names are taken — so the question is how fast
     * somebody can walk a list of candidates, not what each answer costs.
     *
     * @param clientIp the caller's address, or null when it is not known
     */
    Throttle countUsernameLookup(String clientIp);

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
