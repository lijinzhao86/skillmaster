package com.skillmasterai.modules.account;

/**
 * SMS codes: issuing one, and accepting one back.
 *
 * <p>The two methods are the whole of the code's life, and both of them are attempts to spend
 * somebody else's money or to get past a check that exists because a phone number is only
 * <em>claimed</em> until a code sent to it comes back. Hence the rate limiting being here rather
 * than at the call site: it is not decoration around the flow, it is part of what the flow is.
 */
public interface PhoneVerification {

    /**
     * Issues a code and hands it to the {@link SmsSender}, unless a rule refuses first.
     *
     * <p>Issued for any well-formed phone number, registered or not — the caller cannot be told
     * which it was, or this endpoint becomes the account-existence oracle the whole error model
     * avoids. See {@code SendVerificationCodeUseCase} for what that costs.
     *
     * @param clientIp the caller's address, or null when it is not known
     * @return {@link Throttle.Refused} when no code was sent
     */
    Throttle send(String phone, VerificationPurpose purpose, String clientIp);

    /**
     * Accepts a code, once.
     *
     * <p><strong>Callers must declare a transaction that does not roll back for
     * {@link VerificationCodeException}.</strong> A refused guess has to leave its count behind, and
     * the count is the only thing standing between six digits and a few thousand requests. Nothing
     * else here needs to survive, so the declaration is portable only because the caller checks the
     * code before writing anything — see {@code RegisterAccountUseCase}.
     *
     * @throws VerificationCodeException when the code is wrong, has expired, has already been used,
     *         or has been guessed at too many times — one exception for all four, because telling
     *         them apart tells an attacker which part to keep trying
     */
    void consume(String phone, VerificationPurpose purpose, String code);
}
