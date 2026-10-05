package com.skillmasterai.modules.token;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The one relationship between these five numbers that a deployment can get wrong and not notice.
 *
 * <p>The rest of the record's validation is "not null, not zero, not negative", which a bad property
 * file fails at startup with the property named. This is different: a ceiling below the idle
 * lifetime is *individually* valid and makes one of ADR 0024's two rulers unreachable — a refresh
 * token would always hit the ceiling first, and the idle rule would look enforced while never
 * firing.
 */
class TokenPolicyTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration THIRTY_DAYS = Duration.ofDays(30);
    private static final Duration GRACE = Duration.ofSeconds(60);
    private static final Duration CODE = Duration.ofMinutes(5);

    @Test
    void theDesignsNumbersAreAccepted() {
        new TokenPolicy(HOUR, THIRTY_DAYS, Duration.ofDays(180), CODE, GRACE);
    }

    @Test
    void aCeilingBelowTheIdleLifetimeIsRefused() {
        assertThatThrownBy(() -> new TokenPolicy(HOUR, THIRTY_DAYS, Duration.ofDays(7), CODE, GRACE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idle");
    }

    @Test
    void aCeilingEqualToTheIdleLifetimeIsAllowed() {
        // Degenerate but coherent: it means "no idle rule beyond the ceiling", which is a thing a
        // deployment may want and is not the mistake this guard is about.
        new TokenPolicy(HOUR, THIRTY_DAYS, THIRTY_DAYS, CODE, GRACE);
    }

    @Test
    void zeroAndNegativeAreRefusedForEveryOneOfThem() {
        assertThatThrownBy(() -> new TokenPolicy(Duration.ZERO, THIRTY_DAYS, Duration.ofDays(180), CODE, GRACE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenPolicy(HOUR, THIRTY_DAYS, Duration.ofDays(180), CODE, GRACE.negated()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenPolicy(HOUR, THIRTY_DAYS, Duration.ofDays(180), null, GRACE))
                .isInstanceOf(NullPointerException.class);
    }
}
