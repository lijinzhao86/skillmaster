package com.skillmasterai.modules.account;

/**
 * Captcha challenges: issuing one, and accepting an answer.
 *
 * <p>Whoever asks for a challenge is anonymous — that is the point of it — so the challenge is
 * identified by an id handed to the caller rather than by anything about them. The answer never
 * leaves this module in any form: what goes out is the image, and what comes back is checked
 * against a hash.
 *
 * <p><strong>The caller must declare a transaction that does not roll back for
 * {@link AccountRequestException}.</strong> A wrong answer has to leave its count behind, exactly as
 * a wrong SMS code does, or the attempt cap is decoration. See {@code SendVerificationCodeUseCase},
 * where the check sits ahead of every write so the commit carries nothing but the count.
 */
public interface CaptchaChallenge {

    /**
     * Draws a challenge and stores it, so that only the answer is needed to check one later.
     *
     * @throws ThrottledException when this caller has asked for too many challenges
     */
    Issued issue(String clientIp);

    /**
     * Accepts an answer, once.
     *
     * @throws AccountRequestException naming the {@code captcha} field, when the answer is wrong,
     *         has expired, has already been used, or has been guessed at too many times — one
     *         answer for all four, because telling them apart tells an attacker which part to keep
     *         trying
     */
    void consume(String id, String answer);

    /**
     * @param id    what the client sends back with its answer
     * @param image the PNG, base64-encoded
     */
    record Issued(String id, String image) {
    }
}
