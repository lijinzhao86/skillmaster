package com.skillmasterai.modules.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The product's maximum and default, and the one deployment switch (ADR 0171).
 *
 * <p>Two things worth pinning rather than trusting: the default (90) and the maximum (365) are
 * constants, not deployment settings, and the ceiling is the end of the 365th day in Shanghai
 * rather than "now plus the duration" — the console offers days, and a ceiling at a mid-day instant
 * would refuse its own last legal date by however many seconds of today have already gone by.
 */
class ApiKeyPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:30:00Z");

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @Test
    void theDefaultIsNinetyDaysFromNow() {
        ApiKeyPolicy policy = new ApiKeyPolicy(true, TEN_MINUTES);

        // 2026-10-15 + 90 days. Written as the date rather than as a second call to plusDays, so the
        // test fails if the unit ever changes rather than agreeing with whatever the code does.
        assertThat(policy.defaultExpiry(NOW)).isEqualTo(Instant.parse("2027-01-13T09:30:00Z"));
        assertThat(policy.defaultDays()).isEqualTo(90);
    }

    @Test
    void theMaximumIsThreeHundredSixtyFiveDaysAndIsTheEndOfThatDay() {
        ApiKeyPolicy policy = new ApiKeyPolicy(true, TEN_MINUTES);

        // NOW is 09:30Z = 17:30 in Shanghai on 2026-10-15, so day 365 is 2027-10-15 and its last
        // second is 23:59:59 there.
        assertThat(policy.maxLifetimeDays()).isEqualTo(365);
        assertThat(policy.latestDay(NOW)).isEqualTo(LocalDate.parse("2027-10-15"));
        assertThat(policy.latestExpiry(NOW)).isEqualTo(Instant.parse("2027-10-15T15:59:59Z"));
        assertThat(policy.latestExpiry(NOW)).isAfter(policy.defaultExpiry(NOW));

        // The zone is the console's, so its last-second-of-the-day is exactly what the boundary is
        // measured to: 23:59:59 in Shanghai, not 23:59:59 UTC (which would be 08:00 the next morning
        // there, a day's worth of slack this rule does not intend to give).
        assertThat(policy.latestExpiry(NOW)).isNotEqualTo(Instant.parse("2027-10-15T23:59:59Z"));
    }

    @Test
    void theDefaultNeverCrossesTheMaximum() {
        // The smaller of `now + 90 days` and the ceiling is always the default today; the min() is
        // there so a future change to either constant cannot make the default a date this product
        // refuses to issue.
        ApiKeyPolicy policy = new ApiKeyPolicy(true, TEN_MINUTES);

        assertThat(policy.defaultExpiry(NOW)).isBefore(policy.latestExpiry(NOW));
    }

    @Test
    void theSwitchOnlyDecidesWhetherNeverIsAllowed() {
        ApiKeyPolicy allowed = new ApiKeyPolicy(true, TEN_MINUTES);
        ApiKeyPolicy refused = new ApiKeyPolicy(false, TEN_MINUTES);

        assertThat(allowed.allowNoExpiry()).isTrue();
        assertThat(refused.allowNoExpiry()).isFalse();

        // The switch does not change the default: a key that names nothing still gets 90 days.
        assertThat(refused.defaultExpiry(NOW)).isEqualTo(allowed.defaultExpiry(NOW));
    }

    @Test
    void theLastUsedWindowMustBePositive() {
        assertThatThrownBy(() -> new ApiKeyPolicy(true, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> new ApiKeyPolicy(true, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void theLastUsedWindowCannotBeLeftOut() {
        assertThatThrownBy(() -> new ApiKeyPolicy(true, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("last-used-window is required");
    }
}
