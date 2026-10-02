package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.account.AuthThrottle;
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
 * <p><strong>A registration no longer pays that toll on its first send from an address.</strong> The
 * captcha still stands in front of sending, but one send per address per day goes out without it —
 * claimed rather than counted, so that a caller refused for a reason which spends no message gets the
 * allowance back with the rollback. This is a widening and should be read as one: what it gives away
 * is one message per address per window that no human solved anything for, and the caps are
 * unchanged. Recovery keeps the old rule and pays every time; {@link #grantedFreeSend} says why the
 * split falls there.
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
 * behind. That commit can now carry the free-send claim, where it previously carried only the
 * captcha's count — the two never together, which is what the ordering {@link #grantedFreeSend}
 * describes: a granted claim means the captcha is never read, and a claim that was not granted
 * inserted nothing to carry.
 */
@Component
public class SendVerificationCodeUseCase {

    private final PhoneVerification codes;
    private final CaptchaChallenge captchas;
    private final AuthThrottle throttles;

    public SendVerificationCodeUseCase(PhoneVerification codes, CaptchaChallenge captchas,
            AuthThrottle throttles) {
        this.codes = codes;
        this.captchas = captchas;
        this.throttles = throttles;
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
        if (!grantedFreeSend(request.purpose(), clientIp)) {
            captchas.consume(request.captchaId(), request.captchaAnswer());
        }
        if (codes.send(request.phone(), request.purpose(), clientIp)
                instanceof Throttle.Refused refused) {
            // The only refusal reaches the client as a 429 with the wait attached. A code that was
            // not sent must not look like one that was.
            throw new ThrottledException(refused.retryAfterSeconds());
        }
    }

    /**
     * Whether this send goes out without a captcha, claiming the address's free one if so.
     *
     * <p><strong>The three things this method's position decides.</strong> It is claimed after the
     * number is known to be well formed, so a malformed request does not spend the allowance; it is
     * claimed before the captcha is read, so a granted claim means no captcha is consulted at all
     * and a refused one means nothing was written; and it is claimed well before the message goes
     * out, so a throttled send rolls the claim back — no message, no cost.
     *
     * <p>Registration only. Recovery is the flow that can take an account away from whoever owns it,
     * so its sends keep costing a captcha every time; the allowance to be read nothing for is a
     * concession to the common case, not to the dangerous one.
     */
    private boolean grantedFreeSend(VerificationPurpose purpose, String clientIp) {
        return purpose == VerificationPurpose.REGISTER && throttles.claimFreeCodeSend(clientIp);
    }

    /**
     * Whether a send from this caller would be asked for a captcha, answered without spending anything.
     *
     * <p>Here rather than in a use case of its own so that the rule has one home: this is
     * {@link #grantedFreeSend} read instead of taken, and two statements of it would be two things to
     * keep in step. That the answer can go stale between asking and sending is why the refusal path
     * exists at all — see {@link AuthThrottle#freeCodeSendAvailable}.
     *
     * @throws ThrottledException when the caller's address has spent its lookups for the window
     */
    public boolean captchaNeeded(VerificationPurpose purpose, String clientIp) {
        if (throttles.countCodePolicyRead(clientIp) instanceof Throttle.Refused refused) {
            throw new ThrottledException(refused.retryAfterSeconds());
        }
        return purpose != VerificationPurpose.REGISTER
                || !throttles.freeCodeSendAvailable(clientIp);
    }

    /**
     * @param captchaId     the challenge this request claims to have solved
     * @param captchaAnswer what the caller read in the image
     */
    public record Request(String phone, VerificationPurpose purpose, String captchaId,
            String captchaAnswer) {
    }
}
