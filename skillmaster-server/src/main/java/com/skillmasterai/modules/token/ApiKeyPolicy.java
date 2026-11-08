package com.skillmasterai.modules.token;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * How long a key may live in this deployment (ADR 0171).
 *
 * <p><strong>The maximum and the default are product constants; only "may a key be issued with no
 * expiry" is a deployment setting.</strong> The old model had one deployment number doing both jobs
 * (ADR 0042); v53 splits them: {@link #MAX_LIFETIME} (365 days) is the longest any key may live
 * anywhere, {@link #DEFAULT_LIFETIME} (90 days) is the date a request that names nothing gets, and
 * a deployment only decides whether the "never" option appears at all.
 *
 * <p><strong>Why the maximum is a constant rather than a setting.</strong> The product owner chose
 * one product-wide maximum: the form always has the same six choices, and there is no "this
 * deployment hides 365" branch. The cost is that an operator can no longer tighten the cap; a
 * deployment that wants stricter keys has the 90-day default and can forbid "never", but cannot
 * remove the 365-day choice. That is a deliberate trade, recorded in ADR 0171 §后果, not an
 * omission.
 *
 * <p><strong>The third number is not a lifetime</strong> — it is how often the request path is
 * allowed to stamp a key as used. It is here for the reason {@code TokenPolicy} bundles a grace
 * window with its four lifetimes: both are operational dials on this module's behaviour, and
 * splitting them across two records would be a distinction nobody would remember.
 *
 * @param allowNoExpiry     whether a person may choose a key with no date. Off means every key gets
 *                          an expiry; the request path already reads NULL as "never", so this is
 *                          only about what this deployment will issue
 * @param lastUsedWindow    how stale a key's recorded use has to be before the next request rewrites
 *                          it. The request path is otherwise read-only, and without this every command
 *                          an agent runs would be a write. It also sets how finely a change of
 *                          address can be noticed, since the address is written with the stamp
 */
public record ApiKeyPolicy(boolean allowNoExpiry, Duration lastUsedWindow) {

    /** The longest any key may live, everywhere. ADR 0171 §决定 2. */
    public static final Duration MAX_LIFETIME = Duration.ofDays(365);

    /** The date a request that names nothing gets, everywhere. ADR 0171 §决定 1. */
    public static final Duration DEFAULT_LIFETIME = Duration.ofDays(90);

    public ApiKeyPolicy {
        Objects.requireNonNull(lastUsedWindow,
                "skillmaster.api-keys.last-used-window is required");
        if (lastUsedWindow.isZero() || lastUsedWindow.isNegative()) {
            throw new IllegalStateException("the last-used window must be positive: a zero window"
                    + " turns one write per window into one write per request, which is the cost"
                    + " this setting exists to avoid; got " + lastUsedWindow);
        }
    }

    /**
     * The expiry a key issued now gets when the request did not name one; never null.
     *
     * <p><strong>Never beyond this deployment's own ceiling.</strong> {@code now.plus(DEFAULT)} would
     * be inside {@link #latestExpiry} while the maximum is longer than the default, which it always
     * is — but taking the smaller of the two keeps "a default is never something this deployment
     * refuses" true by construction rather than by the current value of two constants.
     */
    public Instant defaultExpiry(Instant now) {
        Instant byDefault = now.plus(DEFAULT_LIFETIME);
        Instant ceiling = latestExpiry(now);
        return byDefault.isAfter(ceiling) ? ceiling : byDefault;
    }

    /**
     * The latest expiry this deployment will issue for a key created at {@code now}; never null.
     *
     * <p><strong>It is the end of a day, not {@code now} plus the duration</strong>, and the difference
     * is not cosmetic: "365 days" is a number of whole days to everybody who reads it, while
     * {@code now.plus(365 days)} is that many days from *this second* — an instant in the middle of a
     * day. A console that lets somebody pick a date means the end of that date, so a ceiling at
     * {@code now + lifetime} would refuse the very day the form offers, on every submission, by the
     * number of seconds already gone by.
     *
     * <p><strong>The zone is the console's, and it has to be.</strong> The form writes dates as
     * Shanghai days (see the console's own decision, technical design §分期 P1 ③), so a ceiling
     * counted in UTC days would land up to a day earlier and refuse the last legal date for the
     * eight hours a day when Shanghai has already rolled over. This is the same trade the console's
     * `time.ts` records for display, made once more for the bound.
     */
    public Instant latestExpiry(Instant now) {
        LocalDate day = latestDay(now);
        // The last second of that day, and built by naming the time rather than by adding 86399
        // seconds to midnight: a day is not always 86400 seconds long (China had summer time until
        // 1991), and the arithmetic that assumes it would answer with the wrong instant on such a
        // day. Unreachable while the clock is the present and the zone is this one, which is exactly
        // why it is worth not encoding the assumption.
        return day.atTime(23, 59, 59).atZone(DAY_ZONE).toInstant();
    }

    /**
     * The last day this deployment will issue a date on; never null.
     *
     * <p>Published to clients as {@code latest_day}, so that a console offers a day this deployment
     * accepts **without recomputing it from its own clock**. The two clocks differ — that is what
     * clocks do — and a form that worked out "365 days from now" locally would refuse itself for
     * however far the client is ahead of the server, in the seconds around a day boundary.
     */
    public LocalDate latestDay(Instant now) {
        return now.atZone(DAY_ZONE).toLocalDate().plusDays(MAX_LIFETIME.toDays());
    }

    /** The default in whole days, for the console's preselect. */
    public long defaultDays() {
        return DEFAULT_LIFETIME.toDays();
    }

    /** The maximum in whole days, for the console's option list. */
    public long maxLifetimeDays() {
        return MAX_LIFETIME.toDays();
    }

    /**
     * The day boundary the ceiling is counted on — see {@link #latestExpiry}.
     *
     * <p>Shanghai, not a configured value: it is the zone the console's date input already means, and
     * making it a setting would let a deployment put the form and the ceiling in different days.
     */
    private static final ZoneId DAY_ZONE = ZoneId.of("Asia/Shanghai");
}
