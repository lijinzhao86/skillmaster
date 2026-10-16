package com.skillmasterai.modules.account;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * How long a browser session lives — and it takes two numbers to say it (ADR 0043).
 *
 * <p>A value type rather than two constants, for the reason §3.1 gives about the token lifetimes:
 * these are numbers a deployment may need to change, and the module cannot read configuration
 * itself — {@code config} builds this and hands it over, the same arrangement M2 has with its five.
 *
 * <p><strong>The two rulers are not the same kind of number, and they are not enforced in the same
 * place.</strong> {@code idle} is how long a session survives untouched, and the framework already
 * implements it: {@code spring.session.timeout} lands on Spring Session's own
 * {@code max_inactive_interval}, and re-implementing it here would be a second truth about the same
 * thing. {@code absolute} is a ceiling on the whole session however much it is used, and nothing in
 * the framework expresses it — {@code SessionAbsoluteLimitFilter} enforces it on every request that
 * carries a session.
 *
 * <p><strong>Which is why the check below is not a nicety.</strong> An absolute ceiling shorter than
 * the idle window is a ceiling that fires first, every time, so the idle rule would never be reached
 * — the framework would look like it was enforcing a ruler that had been silently disabled.
 * Individually both numbers are fine, which is exactly why this has to be refused rather than
 * noticed: the shape {@code TokenPolicy} uses for its own pair, and refused at startup for the same
 * reason.
 *
 * @param idle     how long a session survives with no request at all. Bound to
 *                 {@code spring.session.timeout} as well as read here, from one property, so the
 *                 framework and this record cannot disagree about it — see {@code application.yml}.
 * @param absolute the ceiling on the whole session, counted from the login and not moved by use. Its
 *                 value is not about theft: it is what guarantees a forgotten login stops working,
 *                 and what makes "yes, still me" a question a person is asked at least this often
 *                 (ADR 0043 §理由).
 */
public record SessionPolicy(Duration idle, Duration absolute) {

    public SessionPolicy {
        requirePositive(idle, "session idle lifetime");
        requirePositive(absolute, "session absolute ceiling");

        // Two rulers, and one of them can hide the other. Caught at startup rather than in a year's
        // time, because the deployment that gets this wrong is the one that thinks both are on.
        if (absolute.compareTo(idle) < 0) {
            throw new IllegalStateException("the session absolute ceiling (" + absolute
                    + ") is shorter than its idle lifetime (" + idle
                    + "), which makes the idle rule unreachable");
        }
    }

    /**
     * Whether a session created at {@code created} has reached the absolute ceiling by {@code now}.
     *
     * <p><strong>Inclusive, and that is the rule rather than an accident of the comparison:</strong>
     * a session that is exactly seven days old has been logged in for seven days, and the ceiling is
     * about how long a login lasts rather than about a moment it must still be short of. Typed as
     * {@link Instant} rather than as the framework's epoch milliseconds so that no caller can hand it
     * seconds by mistake — the two are the same number to a compiler.
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
