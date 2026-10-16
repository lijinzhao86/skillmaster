package com.skillmasterai.modules.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The two rulers and the sampling window, at the boundaries — which is where all three rules live.
 *
 * <p>These exist for the reason {@code TokenPolicyTest} and {@code ApiKeyPolicyTest} do: what this
 * type is <em>for</em> is refusing a combination at startup rather than producing one of its two
 * rulers silently never firing, and a rule that is never exercised is a rule nobody knows still holds.
 * The arithmetic is here too, because it is four lines that a reader will otherwise have to trust.
 *
 * <p><strong>The two comparisons are not the same shape as {@code TokenPolicy}'s, and the difference
 * is deliberate</strong> (see the record's javadoc): equal values are refused here, because the slide
 * is capped at the ceiling and a ceiling equal to the idle window would bind on the very first use.
 * That case has its own test below rather than being left to the reader.
 */
class CliSignInPolicyTest {

    private static final Duration IDLE = Duration.ofHours(24);
    private static final Duration ABSOLUTE = Duration.ofDays(7);
    private static final Duration WINDOW = Duration.ofMinutes(5);

    @Test
    void aCeilingShorterThanTheIdleRulerIsRefused() {
        assertThatThrownBy(() -> new CliSignInPolicy(Duration.ofDays(7), Duration.ofHours(24), WINDOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
    }

    /**
     * The case that separates this type from {@code TokenPolicy}: equality is refused, not allowed.
     *
     * <p>With the two equal, every use slides the expiry straight onto the ceiling, so the credential
     * always dies of the absolute ruler and the idle ruler never decides anything — a deployment that
     * set two rulers would have got one.
     */
    @Test
    void aCeilingEqualToTheIdleRulerIsRefusedToo() {
        assertThatThrownBy(() -> new CliSignInPolicy(IDLE, IDLE, WINDOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void aWindowWiderThanATenthOfTheIdleRulerIsRefused() {
        // Two and a half hours against a day is just past the tenth the record documents.
        assertThatThrownBy(() -> new CliSignInPolicy(IDLE, ABSOLUTE, Duration.ofMinutes(150)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenth");
    }

    /** The boundary itself is allowed: the rule is "at most", and the defaults sit far below it. */
    @Test
    void aWindowOfExactlyATenthIsAllowed() {
        assertThat(new CliSignInPolicy(IDLE, ABSOLUTE, IDLE.dividedBy(10)).slideWindow())
                .isEqualTo(Duration.ofMinutes(144));
    }

    @Test
    void aZeroOrNegativeValueIsRefused() {
        assertThatThrownBy(() -> new CliSignInPolicy(Duration.ZERO, ABSOLUTE, WINDOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CliSignInPolicy(IDLE, ABSOLUTE, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** A use inside the window moves the expiry to the idle deadline and no further. */
    @Test
    void aUseSlidesTheExpiryToTheIdleDeadline() {
        Instant created = Instant.parse("2026-10-16T00:00:00Z");
        Instant used = created.plus(Duration.ofHours(2));

        assertThat(new CliSignInPolicy(IDLE, ABSOLUTE, WINDOW).slidExpiry(created, used))
                .isEqualTo(used.plus(IDLE));
    }

    /** Six days in, the idle deadline is a day past the ceiling, so the ceiling is what is written. */
    @Test
    void theCeilingPinsTheSlide() {
        Instant created = Instant.parse("2026-10-16T00:00:00Z");
        Instant used = created.plus(Duration.ofDays(6));

        assertThat(new CliSignInPolicy(IDLE, ABSOLUTE, WINDOW).slidExpiry(created, used))
                .isEqualTo(created.plus(ABSOLUTE));
    }

    /**
     * A use only counts as stale once a whole window has passed — the property that keeps a busy
     * machine from writing on every request.
     */
    @Test
    void aUseIsStaleOnlyAfterAWholeWindow() {
        CliSignInPolicy policy = new CliSignInPolicy(IDLE, ABSOLUTE, WINDOW);
        Instant now = Instant.parse("2026-10-16T12:00:00Z");
        String issued = "just now";

        // What a fresh login holds: now + idle. That is already what a slide would write, so nothing is
        // written — which is why a machine that logs in and runs one command costs no write at all.
        assertThat(policy.stale(now.plus(IDLE), now)).as("%s should not need writing", issued).isFalse();
        // A minute inside the window still does not.
        assertThat(policy.stale(now.plus(IDLE).minus(Duration.ofMinutes(1)), now)).isFalse();
        // A minute past it does.
        assertThat(policy.stale(now.plus(IDLE).minus(Duration.ofMinutes(6)), now)).isTrue();
    }

    /** Inclusive of the endpoint, matching {@code SessionPolicy.pastAbsoluteCeiling}. */
    @Test
    void theCeilingIsReachedAtItsOwnInstant() {
        CliSignInPolicy policy = new CliSignInPolicy(IDLE, ABSOLUTE, WINDOW);
        Instant created = Instant.parse("2026-10-16T00:00:00Z");

        assertThat(policy.pastAbsoluteCeiling(created, created.plus(ABSOLUTE).minusSeconds(1)))
                .isFalse();
        assertThat(policy.pastAbsoluteCeiling(created, created.plus(ABSOLUTE))).isTrue();
    }
}
