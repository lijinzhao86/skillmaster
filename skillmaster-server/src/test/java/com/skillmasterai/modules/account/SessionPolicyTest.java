package com.skillmasterai.modules.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The two rulers, and the two ways one of them can quietly stop being one.
 *
 * <p>The first is a ceiling below the idle window: both numbers are individually fine, and together
 * they mean the idle rule can never fire (ADR 0043 §决定 1). The second is the boundary — a ceiling
 * is only a ceiling if it is measured from the login and nothing moves it, which is why
 * {@link SessionPolicy#pastAbsoluteCeiling} takes a creation and a present and no third thing.
 */
class SessionPolicyTest {

    private static final Duration DAY = Duration.ofHours(24);
    private static final Duration WEEK = Duration.ofDays(7);

    @Test
    void theDesignsNumbersAreAccepted() {
        new SessionPolicy(DAY, WEEK);
    }

    @Test
    void aCeilingBelowTheIdleWindowIsRefused() {
        assertThatThrownBy(() -> new SessionPolicy(DAY, Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idle");
    }

    @Test
    void aCeilingEqualToTheIdleWindowIsAllowed() {
        // Degenerate but coherent: it says "nothing survives past the ceiling anyway", which is not
        // the mistake this guard is about.
        new SessionPolicy(DAY, DAY);
    }

    @Test
    void zeroAndNegativeAndNullAreRefused() {
        assertThatThrownBy(() -> new SessionPolicy(Duration.ZERO, WEEK))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SessionPolicy(DAY, WEEK.negated()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SessionPolicy(null, WEEK))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theCeilingIsReachedAtExactlySevenDaysAndNotBefore() {
        SessionPolicy policy = new SessionPolicy(DAY, WEEK);
        Instant loggedIn = Instant.parse("2026-10-01T00:00:00Z");

        // 6 days 23 hours 59 minutes 59.999 seconds: still usable, and this is accepting #2 — a
        // ceiling computed a second short would pass every other test in the suite.
        assertThat(policy.pastAbsoluteCeiling(loggedIn, loggedIn.plus(WEEK).minusMillis(1)))
                .isFalse();
        // "Logged in for seven days" is seven days, not "seven days and a moment".
        assertThat(policy.pastAbsoluteCeiling(loggedIn, loggedIn.plus(WEEK))).isTrue();
        assertThat(policy.pastAbsoluteCeiling(loggedIn, loggedIn.plus(WEEK).plusSeconds(1))).isTrue();
    }
}
