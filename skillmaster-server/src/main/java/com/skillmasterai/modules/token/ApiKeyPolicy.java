package com.skillmasterai.modules.token;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * How long a key may live in this deployment — two deployment settings (ADR 0038 §决定 3, ADR 0039
 * §决定 1, ADR 0042).
 *
 * <p><strong>One number, two jobs: it is the lifetime a new key gets and the longest one this
 * deployment will issue.</strong> An explicit date beyond it is refused rather than honoured (ADR
 * 0042) — a setting any client could override would be a promise to the operator that cannot be
 * kept, and this is the one security-shaped number a deployment gets to choose. {@code expire} off
 * means no ceiling at all, which is the same decision as "a key may be issued with no expiry".
 *
 * <p><strong>Why a switch and a number rather than just a number that may be empty.</strong> The
 * design says "turn it off and a key may be issued with no expiry", and the obvious spelling — an
 * empty value — does not survive the binder: Spring treats an empty value for a non-text type as
 * <em>the property was not set</em>, so the whole group binds to nothing and the record arrives
 * null, which fails at startup rather than at the first issuance. Measured, not assumed. A named
 * boolean says the same thing in a form that cannot be misread by either a person or a framework.
 *
 * <p><strong>The third number is not a lifetime</strong> — it is how often the request path is
 * allowed to stamp a key as used. It is here for the reason {@code TokenPolicy} bundles a grace
 * window with its four lifetimes: all three are the same kind of thing, an operational dial on this
 * module's behaviour, and splitting them across two records would be a distinction nobody would
 * remember.
 *
 * @param expire            whether keys get a date at all. Off means a key issued without one never
 *                          expires — a state the rest of the path already handles, since
 *                          {@code api_key.expires_at} is nullable and the validator reads NULL as
 *                          "never"
 * @param lifetime          how long a key lasts when the request did not name a date, **and the
 *                          longest date it may name**. Ignored when {@code expire} is off, which is
 *                          why it still has to be a sane number rather than something to leave blank
 * @param lastUsedWindow    how stale a key's recorded use has to be before the next request rewrites
 *                          it. The request path is otherwise read-only, and without this every command
 *                          an agent runs would be a write. It also sets how finely a change of
 *                          address can be noticed, since the address is written with the stamp
 */
public record ApiKeyPolicy(boolean expire, Duration lifetime, Duration lastUsedWindow) {

    public ApiKeyPolicy {
        Objects.requireNonNull(lifetime,
                "skillmaster.api-keys.lifetime is required even when expire is false:"
                        + " leaving it out makes the whole group unbound and the application fail"
                        + " at startup");
        Objects.requireNonNull(lastUsedWindow,
                "skillmaster.api-keys.last-used-window is required");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalStateException("an API key's lifetime must be positive; got " + lifetime);
        }
        // **And it has to be a whole day, because the ceiling is a day boundary.** `latestDay` counts
        // whole days out and `toDays()` truncates, so every lifetime under a day means "zero days" —
        // a ceiling on *today*, which for the seconds after 23:59:59 has already passed. The default
        // is the smaller of `now + lifetime` and that ceiling, so a rotation — which names no date at
        // all — would mint a key that is expired the moment it exists: the console shows a row that
        // says it is expired, and the first request made with it gets `token_expired`, with nothing
        // anywhere saying the deployment's own setting caused it. Refused at startup instead, where
        // the person who wrote the setting is looking, and where the failure costs nothing.
        if (lifetime.toDays() < 1) {
            throw new IllegalStateException("an API key's lifetime must be at least one whole day:"
                    + " the ceiling is the last second of the day this many days out, so anything"
                    + " shorter truncates to today and can already be in the past; got " + lifetime);
        }
        if (lastUsedWindow.isZero() || lastUsedWindow.isNegative()) {
            throw new IllegalStateException("the last-used window must be positive: a zero window"
                    + " turns one write per window into one write per request, which is the cost"
                    + " this setting exists to avoid; got " + lastUsedWindow);
        }
    }

    /**
     * The expiry a key issued now gets when the request did not name one; null means never.
     *
     * <p><strong>Never beyond this deployment's own ceiling.</strong> {@code now.plus(lifetime)} and
     * {@link #latestExpiry} agree whenever the lifetime is a whole number of days, which is what a
     * lifetime is for — but they disagree when it is not ({@code P1DT12H}: the duration reaches into
     * a day the ceiling has already closed), and the default would then be a key this deployment
     * refuses to issue on request. Taking the smaller of the two keeps "one number, one meaning" true
     * at the edge as well as in the middle, at the cost of a request that says nothing getting
     * slightly less than its duration asked for — the same amount, in fact, that a request naming the
     * last legal day would get.
     *
     * <p><strong>The result is always in the future, and that is a property of the constructor rather
     * than of this method.</strong> A lifetime of at least one whole day puts {@link #latestDay} at
     * tomorrow or later, and therefore {@link #latestExpiry} after {@code now} — so the smaller of the
     * two is too. The lifetime the constructor refuses is exactly the one that would break this, which
     * is why the floor lives there and not here.
     */
    public Instant defaultExpiry(Instant now) {
        if (!expire) {
            return null;
        }
        Instant byDuration = now.plus(lifetime);
        Instant ceiling = latestExpiry(now);
        return byDuration.isAfter(ceiling) ? ceiling : byDuration;
    }

    /**
     * The latest expiry this deployment will issue for a key created at {@code now}; null means there
     * is no ceiling.
     *
     * <p><strong>It is the end of a day, not {@code now} plus the duration</strong>, and the difference
     * is not cosmetic: "90 days" is a number of whole days to everybody who reads it, while
     * {@code now.plus(90 days)} is that many days from *this second* — an instant in the middle of a
     * day. A console that lets somebody pick a date means the end of that date, so a ceiling at
     * {@code now + lifetime} would refuse the very day the form preselects, on every submission, by
     * the number of seconds already gone by. Measured: adding these two lines without it turned the
     * console's default form into a 400.
     *
     * <p><strong>The zone is the console's, and it has to be.</strong> The form writes dates as
     * Shanghai days (see the console's own decision, technical design §分期 P1 ③), so a ceiling
     * counted in UTC days would land up to a day earlier and refuse the preselected date for the
     * eight hours a day when Shanghai has already rolled over. One of the two had to give, and it
     * cannot be the console's: {@code expiryText} shows a date, and a date that shifted by a zone
     * would make "expires on the 13th" mean the 12th to somebody reading it from elsewhere. This is
     * the same trade the console's `time.ts` records for display, made once more for the bound —
     * **and it is the first zone assumption on this side of the wire**, which is why it is written
     * down here rather than in a `ZoneId` at a call site.
     */
    public Instant latestExpiry(Instant now) {
        LocalDate day = latestDay(now);
        // The last second of that day, and built by naming the time rather than by adding 86399
        // seconds to midnight: a day is not always 86400 seconds long (China had summer time until
        // 1991), and the arithmetic that assumes it would answer with the wrong instant on such a
        // day. Unreachable while the clock is the present and the zone is this one, which is exactly
        // why it is worth not encoding the assumption.
        return day == null ? null : day.atTime(23, 59, 59).atZone(DAY_ZONE).toInstant();
    }

    /**
     * The last day this deployment will issue a date on; null means there is no ceiling.
     *
     * <p>Published to clients as {@code latest_day}, so that a console offers a day this deployment
     * accepts **without recomputing it from its own clock**. The two clocks differ — that is what
     * clocks do — and a form that worked out "90 days from now" locally would refuse itself for
     * however far the client is ahead of the server, in the seconds around a day boundary. The day is
     * a fact about this deployment, so this is where it is decided.
     */
    public LocalDate latestDay(Instant now) {
        return expire ? now.atZone(DAY_ZONE).toLocalDate().plusDays(lifetime.toDays()) : null;
    }

    /**
     * The day boundary the ceiling is counted on — see {@link #latestExpiry}.
     *
     * <p>Shanghai, not a configured value: it is the zone the console's date input already means, and
     * making it two settings would let a deployment put the form and the ceiling in different days.
     */
    private static final ZoneId DAY_ZONE = ZoneId.of("Asia/Shanghai");
}
