package com.skillmasterai.modules.token;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The two rulers a CLI login credential lives by, and the window its expiry slides on.
 *
 * <p><strong>The rulers are the browser session's, and they are the same numbers.</strong> Not
 * "similar" — the same: idle 24 hours, absolute 7 days, taken from {@code skillmaster.sessions}
 * (v4 Q1, Q6). A login is a login whichever plane it is on, which is also why one screen lists both
 * (「登录设备」). What this type adds is the third number, and that one belongs to this credential
 * alone.
 *
 * <p><strong>The absolute ceiling is stored nowhere: it is {@code created_at} plus this.</strong> A
 * row carrying no second date cannot disagree with the configuration that produced it, and
 * {@code access_token} has no column to spare — v4 §数据模型 decided both rulers would be expressed
 * without adding one.
 *
 * <p><strong>{@code absolute} must be strictly greater than {@code idle}, one notch stricter than
 * {@link TokenPolicy} is about its own pair.</strong> There, equal values merely make the idle rule
 * unreachable. Here they make it wrong: the slide is capped at the ceiling, so a ceiling equal to the
 * idle window binds on the very first use, and the credential dies on the absolute ruler every time.
 * A deployment would have set two rulers and got one.
 *
 * @param idle        how long the credential survives <strong>unused</strong>. Its expiry slides to
 *                    {@code now + idle} on every use, so this reads as "how long a quiet machine keeps
 *                    its login".
 * @param absolute    the ceiling on the whole login, however much it is used. Measured from issuance,
 *                    and it is the reason a forgotten credential stops working at all.
 * @param slideWindow how stale the recorded expiry must be before a request rewrites it. The slide is
 *                    sampled rather than per-request, which is what keeps the request path near
 *                    read-only (v4 §数据模型) — and it is also <strong>how early the idle ruler may
 *                    fire</strong>: a use at {@code t} leaves an expiry no earlier than
 *                    {@code t + idle - slideWindow}, so the credential can die up to one window sooner
 *                    than the ruler it is measured against says.
 */
public record CliSignInPolicy(Duration idle, Duration absolute, Duration slideWindow) {

    public CliSignInPolicy {
        requirePositive(idle, "the login credential's idle ruler");
        requirePositive(absolute, "the login credential's absolute ceiling");
        requirePositive(slideWindow, "the login credential's slide window");

        // Caught at startup rather than in a week's time, for the reason TokenPolicy catches its own
        // pair: a deployment that set these the wrong way round would have a ruler that never fires.
        if (absolute.compareTo(idle) <= 0) {
            throw new IllegalStateException(
                    "the login credential's absolute ceiling (" + absolute
                            + ") is not longer than its idle ruler (" + idle
                            + "), which makes the idle ruler unreachable — every use would slide the"
                            + " expiry straight onto the ceiling");
        }

        // "Far below the idle ruler" has to become a number before it can be checked, and this is it.
        // At the defaults — five minutes against twenty-four hours — the early firing is 0.35%. A
        // deployment that shortens the idle ruler to an hour still passes with the default window
        // (six minutes would be the boundary).
        if (slideWindow.multipliedBy(10).compareTo(idle) > 0) {
            throw new IllegalStateException(
                    "the login credential's slide window (" + slideWindow + ") is more than a tenth"
                            + " of its idle ruler (" + idle + "), so a use could leave the credential"
                            + " dying far earlier than the ruler measuring it says");
        }
    }

    /** Where the expiry moves to on a use: {@code now + idle}, and never past the ceiling. */
    public Instant slidExpiry(Instant created, Instant now) {
        Instant idleDeadline = now.plus(idle);
        Instant ceiling = created.plus(absolute);
        return idleDeadline.isBefore(ceiling) ? idleDeadline : ceiling;
    }

    /**
     * Whether a use should rewrite the row at all.
     *
     * <p><strong>The condition is read off the value being written, so no second column records when
     * the slide last happened.</strong> That works because {@code expires_at} has exactly two writers
     * in a row's life — the issuance and this slide — so {@code expires_at - idle} <em>is</em> the time
     * of the last use, and this predicate is just "that was more than a window ago". A separate stamp
     * would be a second copy of a fact already present, and two copies can disagree.
     *
     * <p>One consequence worth stating: a credential issued and then used once is <strong>not</strong>
     * rewritten. Its expiry starts at {@code issued + idle}, which is already the value a slide would
     * write, so a machine that logs in and runs one command costs nothing on the request path.
     */
    public boolean stale(Instant recorded, Instant now) {
        return recorded.isBefore(now.plus(idle).minus(slideWindow));
    }

    /**
     * Whether the ceiling, rather than idleness, is what has passed — the only thing that can tell the
     * two deaths apart once the expiry slides.
     *
     * <p>Inclusive of the endpoint, matching {@code SessionPolicy.pastAbsoluteCeiling}: the two rules
     * are the same rule, and a boundary that differed by a nanosecond between them would be a bug
     * nobody could reproduce.
     */
    public boolean pastAbsoluteCeiling(Instant created, Instant now) {
        return !now.isBefore(created.plus(absolute));
    }

    private static void requirePositive(Duration value, String what) {
        Objects.requireNonNull(value, what + " is required");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalStateException(what + " must be positive, got " + value);
        }
    }
}
