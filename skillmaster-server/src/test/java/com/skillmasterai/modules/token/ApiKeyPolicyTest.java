package com.skillmasterai.modules.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The expiry switch and the number behind it (ADR 0039 §决定 1, ADR 0042).
 *
 * <p>Two behaviours worth pinning rather than trusting: "off" has to mean a key with no date instead
 * of an error or a date, and the arithmetic has to survive the month boundaries a 90-day default
 * runs into. The binding of these two fields from {@code application.yml} is proved by every
 * integration test, since they all boot the context that reads it.
 */
class ApiKeyPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:30:00Z");

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @Test
    void theDefaultIsNinetyDaysFromNow() {
        ApiKeyPolicy policy = new ApiKeyPolicy(true, Duration.ofDays(90), TEN_MINUTES);

        // 2026-10-15 + 90 days. Written as the date rather than as a second call to plusDays, so the
        // test fails if the unit ever changes rather than agreeing with whatever the code does.
        assertThat(policy.defaultExpiry(NOW)).isEqualTo(Instant.parse("2027-01-13T09:30:00Z"));
    }

    @Test
    void turnedOffItIssuesKeysThatDoNotExpire() {
        ApiKeyPolicy policy = new ApiKeyPolicy(false, Duration.ofDays(90), TEN_MINUTES);

        assertThat(policy.defaultExpiry(NOW)).isNull();
        assertThat(policy.expire()).isFalse();
    }

    @Test
    void theSameNumberIsAlsoTheLatestDateThisDeploymentIssues() {
        ApiKeyPolicy policy = new ApiKeyPolicy(true, Duration.ofDays(90), TEN_MINUTES);

        // One number, two jobs (ADR 0042) — but not the same instant, and the difference is the whole
        // reason this is two methods. The default is 90 days from *this second*; the ceiling is the end
        // of the 90th day, because "90 days" means whole days to whoever reads it and because the
        // console offers days. NOW is 09:30Z = 17:30 in Shanghai on 2026-10-15, so day 90 is
        // 2027-01-13 and its last second is 23:59:59 there.
        assertThat(policy.defaultExpiry(NOW)).isEqualTo(Instant.parse("2027-01-13T09:30:00Z"));
        assertThat(policy.latestExpiry(NOW)).isEqualTo(Instant.parse("2027-01-13T15:59:59Z"));
        assertThat(policy.latestExpiry(NOW)).isAfter(policy.defaultExpiry(NOW));

        // And with the switch off there is no ceiling at all: "this deployment does not care about
        // expiry" is one decision, not half of one.
        assertThat(new ApiKeyPolicy(false, Duration.ofDays(90), TEN_MINUTES).latestExpiry(NOW)).isNull();

        // The zone is the console's, so its last-second-of-the-day is exactly what the boundary is
        // measured to: 23:59:59 in Shanghai, not 23:59:59 UTC (which would be 08:00 the next morning
        // there, a day's worth of slack this rule does not intend to give).
        assertThat(policy.latestExpiry(NOW)).isNotEqualTo(Instant.parse("2027-01-13T23:59:59Z"));
    }

    @Test
    void aLifetimeThatIsNotWholeDaysStillLeavesTheDefaultUnderTheCeiling() {
        // `P1DT12H` is a legal lifetime and a strange one: thirty-six hours from now reaches into a
        // day the ceiling has already closed, so `now + lifetime` would be a default this deployment
        // refuses to issue on request. Whichever of the two is smaller wins, so the promise "this is
        // the longest date here" holds for the default as well as for a request that names a date.
        ApiKeyPolicy policy = new ApiKeyPolicy(true, Duration.ofHours(36), TEN_MINUTES);

        // NOW is 17:30 in Shanghai on 2026-10-15, so the ceiling is the end of 2026-10-16 and
        // thirty-six hours reaches past it, into the 17th.
        assertThat(policy.latestExpiry(NOW)).isEqualTo(Instant.parse("2026-10-16T15:59:59Z"));
        assertThat(policy.defaultExpiry(NOW)).isEqualTo(policy.latestExpiry(NOW));

        // And a lifetime that is shorter than its own day boundary is not touched by this: a whole
        // number of days leaves `now + lifetime` inside the day the ceiling closes.
        ApiKeyPolicy whole = new ApiKeyPolicy(true, Duration.ofDays(1), TEN_MINUTES);
        assertThat(whole.defaultExpiry(NOW)).isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void aLifetimeThatIsNotPositiveIsRefusedAtStartup() {
        // A deployment that wrote PT0S meant something, and it was almost certainly not "every key
        // expires the instant it is made" — which is what zero would do, silently, to every key.
        assertThatThrownBy(() -> new ApiKeyPolicy(true, Duration.ZERO, TEN_MINUTES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> new ApiKeyPolicy(true, Duration.ofDays(-1), TEN_MINUTES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void aLifetimeShorterThanADayIsRefusedAtStartup() {
        // **The one lifetime the ceiling cannot mean anything for.** `toDays()` truncates, so anything
        // under a day gives a ceiling on *today*, and after 23:59:59 that ceiling is behind the clock.
        // Whichever is smaller wins, so the default would be that past instant — and a rotation names
        // no date, so it takes the default: the key is expired as it is created, and the console shows
        // a row saying 已过期 with nothing anywhere blaming the deployment's own setting.
        assertThatThrownBy(() -> new ApiKeyPolicy(true, Duration.ofHours(12), TEN_MINUTES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one whole day");

        // A day written as hours is a day: the floor is on what the duration *means*, not on how it
        // was spelled.
        assertThat(new ApiKeyPolicy(true, Duration.ofHours(24), TEN_MINUTES).latestDay(NOW))
                .isEqualTo(LocalDate.parse("2026-10-16"));
    }

    @Test
    void theLifetimeCannotBeLeftOut() {
        // Not a language-level impossibility — the component is simply a Duration — but this is the
        // failure that would otherwise reach a deployment as "the application will not start", so it
        // gets a test that says why.
        assertThatThrownBy(() -> new ApiKeyPolicy(true, null, TEN_MINUTES))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("lifetime is required");
    }
}
