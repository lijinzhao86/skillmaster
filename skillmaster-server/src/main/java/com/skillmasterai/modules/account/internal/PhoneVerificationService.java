package com.skillmasterai.modules.account.internal;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AuthThrottle;
import com.skillmasterai.modules.account.PhoneCipher;
import com.skillmasterai.modules.account.PhoneVerification;
import com.skillmasterai.modules.account.SmsSender;
import com.skillmasterai.modules.account.Throttle;
import com.skillmasterai.modules.account.VerificationCodeException;
import com.skillmasterai.modules.account.VerificationPurpose;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/**
 * Issues verification codes and accepts them once.
 *
 * <p>Two numbers decide how much this can cost an attacker, and neither is the hash. The code is
 * hashed so a leaked table is not a list of live codes, but six digits are enumerable in
 * milliseconds — <strong>what actually bounds the attack is the five-minute window and the three
 * attempts.</strong> Anything that lengthens either of those shortens the other's job.
 */
public final class PhoneVerificationService implements PhoneVerification {

    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 3;
    private static final int CODE_BOUND = 1_000_000;

    private final PhoneVerificationRepository codes;
    private final SmsSender sms;
    private final AuthThrottle throttle;
    private final PhoneCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public PhoneVerificationService(PhoneVerificationRepository codes, SmsSender sms,
            AuthThrottle throttle, PhoneCipher cipher) {
        this.codes = codes;
        this.sms = sms;
        this.throttle = throttle;
        this.cipher = cipher;
    }

    @Override
    public Throttle send(String phone, VerificationPurpose purpose, String clientIp) {
        Throttle verdict = throttle.countCodeSend(phone, clientIp);
        if (verdict instanceof Throttle.Refused) {
            return verdict;
        }
        String code = sixDigits();
        String expiresAt = Timestamps.format(Instant.now().plus(LIFETIME));
        codes.insert(cipher.hash(phone), purpose.wireName(), codeHash(phone, code), expiresAt);
        // After the row is stored: a sender that throws must leave a code that was never delivered
        // rather than a code nobody can check. The caller turns the failure into a 500, and the
        // row expires on its own.
        sms.send(phone, code);
        return new Throttle.Allowed();
    }

    @Override
    public void consume(String phone, VerificationPurpose purpose, String code) {
        String phoneHash = cipher.hash(phone);
        VerificationRow row = codes.findLatestUnconsumed(phoneHash, purpose.wireName())
                .orElseThrow(() -> new VerificationCodeException("no code is outstanding"));

        if (isExpired(row)) {
            // Marked consumed rather than left alone, so the sweep of live rows does not keep
            // handing back a code that can no longer be used.
            codes.consume(row.id(), Timestamps.now());
            throw new VerificationCodeException("the code expired");
        }
        // One of the three attempts is claimed before the code is compared, so that two guesses
        // arriving together cannot both spend the same one. A correct code spends one too, which
        // costs nothing — the row is about to be consumed either way.
        if (!codes.claimAttempt(row.id(), MAX_ATTEMPTS)) {
            throw new VerificationCodeException("too many wrong attempts");
        }
        if (!matches(row.codeHash(), codeHash(phone, code))) {
            throw new VerificationCodeException("the code did not match");
        }
        if (!codes.consume(row.id(), Timestamps.now())) {
            // Somebody else used this code between the read above and this write. Whoever they were
            // has spent it; a code is good for one registration.
            throw new VerificationCodeException("the code was already used");
        }
    }

    private static boolean isExpired(VerificationRow row) {
        // Compared as text, which is sound for the same reason the audit index sorts by it: every
        // timestamp in this schema is written in one fixed-width RFC3339 UTC form.
        return row.expiresAt().compareTo(Timestamps.now()) < 0;
    }

    /** A wrong code and a right one take the same time to reject, so the comparison is not a timer. */
    private static boolean matches(String stored, String presented) {
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The value stored for a code.
     *
     * <p>Includes the phone number so that a hash lifted from one row cannot be replayed against
     * another number's row — the rows are keyed by phone, but the hash itself should not be
     * portable between them.
     */
    private static String codeHash(String phone, String code) {
        return Sha256Hex.of((phone + code).getBytes(StandardCharsets.UTF_8));
    }

    private String sixDigits() {
        return String.format("%06d", random.nextInt(CODE_BOUND));
    }
}
