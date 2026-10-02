package com.skillmasterai.modules.account.internal;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AuthThrottle;
import com.skillmasterai.modules.account.PhoneCipher;
import com.skillmasterai.modules.account.Throttle;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The counters, in PostgreSQL, in one table.
 *
 * <p><strong>Fixed windows, and that is a choice with a known edge.</strong> A window is identified
 * by truncating the clock, so a caller can spend one budget just before a boundary and the next
 * just after: two sends a second apart, for a rule that says one per minute. A sliding log would
 * close that, at a row per attempt instead of a row per window. For a sixty-second cooldown that is
 * a worse trade, and the gap is bounded by the window rather than unbounded.
 *
 * <p>It applies to the long windows too, and more so: the daily cap can be spent twice within a
 * second of midnight, so the worst day costs twice what the rule says. Same trade, same answer —
 * a row per window instead of a row per attempt, and a bound that is a multiple rather than none.
 *
 * <p>The counter is incremented by the same statement that reads it — {@code ON CONFLICT DO UPDATE
 * ... RETURNING} — which is what makes it correct under concurrency without a lock. A read followed
 * by a write would let two callers both see 9.
 */
public final class PgAuthThrottle implements AuthThrottle {

    /**
     * Every rule M1 has, together in one place: what is counted, over what window, up to what cap.
     *
     * <p>Here rather than in configuration because none of these numbers is a deployment's
     * business — they are the shape of the defence, and a deployment that could set the SMS cap to
     * ten thousand is a deployment whose SMS bill is somebody else's problem.
     */
    private enum Rule {

        /** One message per phone per minute. The user-visible "wait a moment" rule. */
        SMS_COOLDOWN("sms:cooldown", 60, 1),
        /** Ten messages per phone per day. The one that bounds what a determined person can cost. */
        SMS_DAILY("sms:daily", 86_400, 10),
        /** Thirty per address per hour — the only rule that notices an attacker with a list. */
        SMS_IP("sms:ip", 3_600, 30),
        /**
         * One message per address per day that goes out without a captcha.
         *
         * <p>Claimed rather than counted — see {@link #claimFreeCodeSend} — so the {@code cap} of one
         * is enforced by the row's existence rather than by a comparison against it. This is the
         * concession that lets an ordinary registration read no image at all: the first send from an
         * address is free to the caller, and every send after it costs a captcha.
         *
         * <p>A deliberate widening, not a tightening. Every send used to require a captcha; this
         * gives one away per address per window, and the caps above are untouched — so what an
         * attacker gains is one message per address per window that no human had to solve anything
         * for. See ADR 0020.
         */
        SMS_FREE("sms:free", 86_400, 1),
        /** Ten attempts at one phone per fifteen minutes; a successful sign-in clears the count. */
        LOGIN_PHONE("login:phone", 900, 10),
        /**
         * Fifty **failed** logins per address per fifteen minutes, for the same reason as SMS_IP.
         *
         * <p>Only failures are counted. An address is shared — an office, a carrier's NAT — so
         * counting the successful sign-ins too would lock out everyone behind it, and a counter
         * cleared by success would let an attacker who owns one account reset the budget he is
         * spending on the others.
         */
        LOGIN_IP("login:ip", 900, 50),
        /**
         * A hundred and twenty challenges per address per hour.
         *
         * <p>Generous on purpose. This rule is not about cost — drawing an image is cheap — but
         * about how fast an anonymous caller can make this service store rows, and a person who has
         * to retype a captcha a few times must never meet it.
         */
        CAPTCHA_IP("captcha:ip", 3_600, 120),
        /**
         * Sixty username lookups per address per fifteen minutes.
         *
         * <p>Not about cost — the two reads behind it are indexed — but about a free, anonymous,
         * unauthenticated endpoint that answers a question about names. Generous enough that a person
         * typing a few candidates, or re-checking one after fixing a typo, never meets it; tight
         * enough that walking a dictionary of them is not something this service will do quickly.
         */
        USERNAME_IP("username:ip", 900, 60),
        /**
         * A hundred and twenty reads of the free-send allowance per address per fifteen minutes.
         *
         * <p>The cheapest thing counted here — one indexed row — and the reason it is counted anyway
         * is the same as for {@code username:ip}: free, anonymous and unlimited is a combination
         * something will eventually lean on. A form asks once as it opens, so this is far above what
         * any real client needs.
         */
        CODE_POLICY_IP("register:policy", 900, 120);

        private final String scope;
        private final long windowSeconds;
        private final int cap;

        Rule(String scope, long windowSeconds, int cap) {
            this.scope = scope;
            this.windowSeconds = windowSeconds;
            this.cap = cap;
        }
    }

    /** Longer than the longest window, so a sweep never removes a row a rule is still counting. */
    private static final Duration KEEP_FOR = Duration.ofHours(25);

    private final JdbcClient jdbc;
    private final PhoneCipher cipher;

    public PgAuthThrottle(JdbcClient jdbc, PhoneCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public Throttle countCodeSend(String phone, String clientIp) {
        String phoneKey = cipher.hash(phone);
        Optional<Throttle.Refused> refusal = spend(Rule.SMS_COOLDOWN, phoneKey);
        if (refusal.isEmpty()) {
            refusal = spend(Rule.SMS_DAILY, phoneKey);
        }
        if (refusal.isEmpty() && clientIp != null) {
            refusal = spend(Rule.SMS_IP, addressKey(clientIp));
        }
        // Each rule is only counted once the ones before it allowed the send. A request that was
        // refused should not also spend the caller's daily budget.
        return refusal.isEmpty() ? new Throttle.Allowed() : refusal.get();
    }

    @Override
    public Throttle countLoginAttempt(String phone) {
        Optional<Throttle.Refused> refusal = spend(Rule.LOGIN_PHONE, cipher.hash(phone));
        return refusal.isEmpty() ? new Throttle.Allowed() : refusal.get();
    }

    @Override
    public Throttle recordLoginFailure(String clientIp) {
        if (clientIp == null) {
            return new Throttle.Allowed();
        }
        Optional<Throttle.Refused> refusal = spend(Rule.LOGIN_IP, addressKey(clientIp));
        return refusal.isEmpty() ? new Throttle.Allowed() : refusal.get();
    }

    @Override
    public Throttle countCaptchaIssue(String clientIp) {
        Optional<Throttle.Refused> refusal =
                clientIp == null ? Optional.empty() : spend(Rule.CAPTCHA_IP, addressKey(clientIp));
        return refusal.isEmpty() ? new Throttle.Allowed() : refusal.get();
    }

    @Override
    public boolean freeCodeSendAvailable(String clientIp) {
        if (clientIp == null) {
            return false;
        }
        // A read, and that is the whole difference from `claimFreeCodeSend`: asking what the next send
        // will cost must not be the thing that spends the allowance.
        return jdbc.sql("SELECT 1 FROM auth_throttle WHERE scope = :scope AND key_hash = :key"
                        + " AND window_start = :window")
                .param("scope", Rule.SMS_FREE.scope)
                .param("key", addressKey(clientIp))
                .param("window", windowStart(Instant.now(), Rule.SMS_FREE.windowSeconds))
                .query(Integer.class)
                .optional()
                .isEmpty();
    }

    @Override
    public Throttle countCodePolicyRead(String clientIp) {
        Optional<Throttle.Refused> refusal = clientIp == null
                ? Optional.empty()
                : spend(Rule.CODE_POLICY_IP, addressKey(clientIp));
        return refusal.isEmpty() ? new Throttle.Allowed() : refusal.get();
    }

    @Override
    public Throttle countUsernameLookup(String clientIp) {
        Optional<Throttle.Refused> refusal = clientIp == null
                ? Optional.empty()
                : spend(Rule.USERNAME_IP, addressKey(clientIp));
        return refusal.isEmpty() ? new Throttle.Allowed() : refusal.get();
    }

    @Override
    public boolean claimFreeCodeSend(String clientIp) {
        if (clientIp == null) {
            return false;
        }
        Instant now = Instant.now();
        // DO NOTHING rather than DO UPDATE: what is being asked is whether this window already has a
        // row, not how many attempts it holds. The insert and the answer are one statement, so two
        // requests arriving together cannot both be told yes.
        int claimed = jdbc.sql("INSERT INTO auth_throttle (scope, key_hash, window_start, attempts)"
                        + " VALUES (:scope, :key, :window, 1)"
                        + " ON CONFLICT (scope, key_hash, window_start) DO NOTHING")
                .param("scope", Rule.SMS_FREE.scope)
                .param("key", addressKey(clientIp))
                .param("window", windowStart(now, Rule.SMS_FREE.windowSeconds))
                .update();
        sweep(now);
        return claimed > 0;
    }

    @Override
    public void clearLoginFailures(String phone) {
        jdbc.sql("DELETE FROM auth_throttle WHERE scope = :scope AND key_hash = :key")
                .param("scope", Rule.LOGIN_PHONE.scope)
                .param("key", cipher.hash(phone))
                .update();
    }

    private Optional<Throttle.Refused> spend(Rule rule, String keyHash) {
        Instant now = Instant.now();
        int attempts = jdbc.sql("INSERT INTO auth_throttle (scope, key_hash, window_start, attempts)"
                        + " VALUES (:scope, :key, :window, 1)"
                        + " ON CONFLICT (scope, key_hash, window_start)"
                        + " DO UPDATE SET attempts = auth_throttle.attempts + 1"
                        + " RETURNING attempts")
                .param("scope", rule.scope)
                .param("key", keyHash)
                .param("window", windowStart(now, rule.windowSeconds))
                .query(Integer.class)
                .single();
        sweep(now);
        return attempts > rule.cap
                ? Optional.of(new Throttle.Refused(secondsLeft(now, rule.windowSeconds)))
                : Optional.empty();
    }

    /**
     * Deletes rows no rule can still be counting.
     *
     * <p>Opportunistic, on the cold path these endpoints already are, rather than a scheduled job:
     * a table that holds at most a day of counters does not justify a background thread. The delete
     * has no index on {@code window_start} — the primary key leads with {@code scope} — and that is
     * affordable for the same reason: what it scans is what the same statement keeps bounded, one
     * row per key per window and nothing older than a day.
     */
    private void sweep(Instant now) {
        jdbc.sql("DELETE FROM auth_throttle WHERE window_start < :cutoff")
                .param("cutoff", Timestamps.format(now.minus(KEEP_FOR)))
                .update();
    }

    /**
     * Which window a moment falls in, as the text the column stores.
     *
     * <p>Truncating the epoch to a multiple of the window rather than keeping a "first seen" time:
     * a window has to be a fixed interval that every server agrees on, or two of them would count
     * the same caller into different rows.
     */
    private static String windowStart(Instant now, long windowSeconds) {
        long start = now.getEpochSecond() / windowSeconds * windowSeconds;
        return Timestamps.format(Instant.ofEpochSecond(start));
    }

    private static long secondsLeft(Instant now, long windowSeconds) {
        long end = (now.getEpochSecond() / windowSeconds + 1) * windowSeconds;
        return end - now.getEpochSecond();
    }

    /**
     * An address, hashed.
     *
     * <p>Plain SHA-256 and not the keyed hash the phone numbers get: an address is not a secret, and
     * the only reason not to store it outright is that a table of who called from where is a
     * liability nobody asked for.
     */
    private static String addressKey(String clientIp) {
        return Sha256Hex.of(clientIp.getBytes(StandardCharsets.UTF_8));
    }
}
