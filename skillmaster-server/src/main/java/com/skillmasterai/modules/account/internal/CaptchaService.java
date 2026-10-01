package com.skillmasterai.modules.account.internal;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.account.AuthThrottle;
import com.skillmasterai.modules.account.CaptchaChallenge;
import com.skillmasterai.modules.account.CaptchaRenderer;
import com.skillmasterai.modules.account.Throttle;
import com.skillmasterai.modules.account.ThrottledException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Issues captcha challenges and accepts answers once.
 *
 * <p><strong>What actually makes this hard to get past is the clock and the counter, not the
 * image.</strong> Four characters from a 32-character alphabet are about a million values, which an
 * automated reader — or a person being paid a fraction of a cent — gets through, and which a script
 * could enumerate offline against a leaked table in seconds. So the hash is not a defence, and the
 * image is a speed bump. The five-minute life, the three attempts and the issuance limit are the
 * parts that hold, which is the same shape as {@code PhoneVerificationService} and for the same
 * reason.
 */
public final class CaptchaService implements CaptchaChallenge {

    private static final String FIELD = "captcha";
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 3;

    private final CaptchaRepository challenges;
    private final CaptchaRenderer renderer;
    private final AuthThrottle throttle;

    public CaptchaService(CaptchaRepository challenges, CaptchaRenderer renderer,
            AuthThrottle throttle) {
        this.challenges = challenges;
        this.renderer = renderer;
        this.throttle = throttle;
    }

    @Override
    public Issued issue(String clientIp) {
        if (throttle.countCaptchaIssue(clientIp) instanceof Throttle.Refused refused) {
            throw new ThrottledException(refused.retryAfterSeconds());
        }
        CaptchaRenderer.Rendered rendered = renderer.render();
        String expiresAt = Timestamps.format(Instant.now().plus(LIFETIME));
        return new Issued(challenges.insert(answerHash(rendered.answer()), expiresAt),
                rendered.image());
    }

    @Override
    public void consume(String id, String answer) {
        if (id == null || id.isBlank() || answer == null || answer.isBlank()) {
            // A client that sent nothing has a bug to fix; one whose answer was wrong gets the same
            // answer as every other refusal. Neither tells anybody anything about the stored answer.
            throw new AccountRequestException(FIELD, "required");
        }
        CaptchaRow row = challenges.find(id)
                .orElseThrow(() -> new AccountRequestException(FIELD, "invalid"));

        if (isExpired(row)) {
            // Marked consumed rather than left alone, so a challenge that ran out of time stops
            // being retried — and so the sweep can tell it has been dealt with.
            challenges.consume(row.id(), Timestamps.now());
            throw new AccountRequestException(FIELD, "invalid");
        }
        // One of the three attempts is claimed before the answer is compared, so that two requests
        // arriving together cannot both spend the same one. A correct answer spends one too, which
        // costs nothing — the challenge is about to be consumed either way.
        if (!challenges.claimAttempt(row.id(), MAX_ATTEMPTS)) {
            throw new AccountRequestException(FIELD, "invalid");
        }
        if (!matches(row.answerHash(), answerHash(answer))) {
            throw new AccountRequestException(FIELD, "invalid");
        }
        if (!challenges.consume(row.id(), Timestamps.now())) {
            // Somebody else answered this challenge between the read above and this write. Whoever
            // they were has spent it; a challenge is good for one request.
            throw new AccountRequestException(FIELD, "invalid");
        }
    }

    private static boolean isExpired(CaptchaRow row) {
        // Compared as text, which is sound for the same reason the audit index sorts by it: every
        // timestamp in this schema is written in one fixed-width RFC3339 UTC form.
        return row.expiresAt().compareTo(Timestamps.now()) < 0;
    }

    /** A wrong answer and a right one take the same time to reject, so the check is not a timer. */
    private static boolean matches(String stored, String presented) {
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The value stored for an answer.
     *
     * <p>Upper-cased first, because {@code SecureCodeGenerator} emits upper case and somebody typing
     * {@code abcd} has answered correctly. The same normalisation has to happen on both sides, which
     * is why it lives here rather than at the call site.
     */
    private static String answerHash(String answer) {
        return Sha256Hex.of(answer.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }
}
