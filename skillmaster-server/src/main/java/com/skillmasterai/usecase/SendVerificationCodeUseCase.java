package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.account.CaptchaChallenge;
import com.skillmasterai.modules.account.PhoneNumberPolicy;
import com.skillmasterai.modules.account.PhoneVerification;
import com.skillmasterai.modules.account.Throttle;
import com.skillmasterai.modules.account.ThrottledException;
import com.skillmasterai.modules.account.VerificationPurpose;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends the SMS code that starts a registration or a password reset.
 *
 * <p><strong>A code goes out for any well-formed number, whether or not it has an account.</strong>
 * That is the whole design of this endpoint. Answering differently — refusing to send to a number
 * nobody registered — would make it an account-existence oracle, the same mistake as answering 403
 * instead of 404 for a private skill. The price is one wasted message when somebody starts
 * registering a number they already registered, and the rate limits are what bound that price.
 *
 * <p><strong>The captcha is checked before the rate limits, and that order is not arbitrary.</strong>
 * These counters count messages that were about to be sent, so a request refused for a wrong captcha
 * must not move them: otherwise a person who mistyped a letter would be told to wait a minute, and
 * the count would be of attempts rather than of sends. The cost is that somebody who has already
 * spent their budget still has to read an image to be told so, which is the right way round — it
 * makes the captcha a toll on the attacker rather than on the throttled user.
 *
 * <p><strong>One case does not hold that way round, and it is worth knowing.</strong> When the
 * throttle refuses, the {@link ThrottledException} rolls this transaction back and the captcha
 * consumed above goes with it, so a caller who is refused may solve the same challenge again while
 * it lives — five minutes, which is longer than the minute the cooldown makes them wait. (Only the
 * cooldown: the address rule's window is an hour, so a challenge has expired long before that one
 * would let them retry.) Nothing comes of it — the replay can only ever authorize a send the rules
 * already allow, so it buys one saved image read and never an extra message. What keeps the rules
 * *after* a refused one unspent is not this rollback but the ordering in
 * {@code PgAuthThrottle.countCodeSend}: each rule is only counted once the ones before it allowed.
 * The rollback's own effect is on the rules already counted.
 *
 * <p>{@link AccountRequestException} is declared as the exception that does not roll this back, for
 * the reason {@code RegisterAccountUseCase} gives at length: a refused answer has to leave its count
 * behind. The check is ahead of every write here too, so the commit carries nothing but the count.
 */
@Component
public class SendVerificationCodeUseCase {

    private final PhoneVerification codes;
    private final CaptchaChallenge captchas;

    public SendVerificationCodeUseCase(PhoneVerification codes, CaptchaChallenge captchas) {
        this.codes = codes;
        this.captchas = captchas;
    }

    /**
     * @param clientIp the caller's address, or null when it is not known
     * @throws AccountRequestException when the number is not one a message could reach, or the
     *         captcha was not solved
     * @throws ThrottledException when the caller has spent its budget for this window
     */
    @Transactional(noRollbackFor = AccountRequestException.class)
    public void send(Request request, String clientIp) {
        PhoneNumberPolicy.problemWith(request.phone()).ifPresent(issue -> {
            throw new AccountRequestException("phone", issue);
        });
        captchas.consume(request.captchaId(), request.captchaAnswer());
        if (codes.send(request.phone(), request.purpose(), clientIp)
                instanceof Throttle.Refused refused) {
            // The only refusal reaches the client as a 429 with the wait attached. A code that was
            // not sent must not look like one that was.
            throw new ThrottledException(refused.retryAfterSeconds());
        }
    }

    /**
     * @param captchaId     the challenge this request claims to have solved
     * @param captchaAnswer what the caller read in the image
     */
    public record Request(String phone, VerificationPurpose purpose, String captchaId,
            String captchaAnswer) {
    }
}
