package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.activity.UsageHistory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** The range parser both planes share (v60): instants in, a validated window out. */
class UsageWindowsTest {

    @Test
    void noRangeKeepsTheV58NinetyDayWindow() {
        UsageHistory.Window window = UsageWindows.parse(null, null, null);

        assertThat(window.days()).isEqualTo(UsageHistory.WINDOW_DAYS);
        assertThat(window.skill()).isNull();
        assertThat(Instant.parse(window.until()))
                .isBetween(Instant.now().minusSeconds(5), Instant.now().plusSeconds(5));
    }

    @Test
    void theDefaultWindowIsReadOffTheGivenClock() {
        Instant now = Instant.parse("2026-11-11T10:00:00Z");

        UsageHistory.Window window = UsageWindows.parse(null, null, null, now);

        assertThat(window.since()).isEqualTo("2026-08-13T10:00:00Z");
        // One second ahead: the same second's writes stay readable (half-open window).
        assertThat(window.until()).isEqualTo("2026-11-11T10:00:01Z");
        assertThat(window.days()).isEqualTo(90);
    }

    @Test
    void aRangeIsEchoedBackWithItsWholeDayCount() {
        UsageHistory.Window window = UsageWindows.parse(
                Instant.now().minus(7, ChronoUnit.DAYS).toString(),
                Instant.now().minus(1, ChronoUnit.MINUTES).toString(), "  pdf  ");

        assertThat(window.days()).isEqualTo(7);
        assertThat(window.skill()).isEqualTo("pdf");
    }

    @Test
    void oneEndOfTheRangeWithoutTheOtherIsRefused() {
        assertThatThrownBy(() -> UsageWindows.parse(Instant.now().toString(), null, null))
                .isInstanceOf(RequestException.class)
                .hasMessageContaining("since 与 until");
        assertThatThrownBy(() -> UsageWindows.parse(null, Instant.now().toString(), null))
                .isInstanceOf(RequestException.class)
                .hasMessageContaining("since 与 until");
    }

    @Test
    void theWindowMustMoveForward() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> UsageWindows.parse(now.toString(), now.toString(), null))
                .isInstanceOf(RequestException.class)
                .hasMessageContaining("until 要晚于 since");
    }

    @Test
    void aWindowLongerThanNinetyDaysIsRefused() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> UsageWindows.parse(
                now.minus(92, ChronoUnit.DAYS).toString(), now.toString(), null))
                .isInstanceOf(RequestException.class)
                .hasMessageContaining("跨度最多 90 天");
    }

    @Test
    void aStartBeforeTheRetentionBoundaryIsRefused() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> UsageWindows.parse(
                now.minus(92, ChronoUnit.DAYS).toString(),
                now.minus(89, ChronoUnit.DAYS).toString(), null))
                .isInstanceOf(RequestException.class)
                .hasMessageContaining("只能看最近 90 天");
    }

    @Test
    void theRetentionBoundaryCarriesTwoHoursOfTimezoneSlack() {
        Instant now = Instant.parse("2026-11-11T10:00:00Z");
        Instant oldest = now.minus(90, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS);

        UsageHistory.Window window = UsageWindows.parse(oldest.toString(), now.toString(), null,
                now);
        assertThat(window.days()).isEqualTo(90);

        // A short window entirely before the boundary reaches the lower-bound check, not the span.
        assertThatThrownBy(() -> UsageWindows.parse(oldest.minusSeconds(1).toString(),
                oldest.minusSeconds(1).plus(1, ChronoUnit.DAYS).toString(), null, now))
                .isInstanceOf(RequestException.class)
                .hasMessageContaining("只能看最近 90 天");

        // One second past the span limit is refused even when the start is inside the retention.
        Instant inside = now.minus(1, ChronoUnit.DAYS);
        assertThatThrownBy(() -> UsageWindows.parse(
                inside.minus(90, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS).minusSeconds(1)
                        .toString(), inside.toString(), null, now))
                .isInstanceOf(RequestException.class)
                .hasMessageContaining("跨度最多 90 天");
    }

    @Test
    void theNextLocalMidnightIsAcceptedButAnArbitraryFutureIsNot() {
        Instant now = Instant.now();
        UsageHistory.Window window = UsageWindows.parse(
                now.minus(1, ChronoUnit.DAYS).toString(), now.plus(1, ChronoUnit.HOURS).toString(),
                null);
        assertThat(window.until()).isNotBlank();

        assertThatThrownBy(() -> UsageWindows.parse(
                now.minus(1, ChronoUnit.DAYS).toString(), now.plus(30, ChronoUnit.HOURS).toString(),
                null))
                .isInstanceOf(RequestException.class)
                .hasMessageContaining("不能晚于今天");
    }

    @Test
    void aFutureStartIsRefused() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> UsageWindows.parse(now.plus(1, ChronoUnit.HOURS).toString(),
                now.plus(2, ChronoUnit.HOURS).toString(), null))
                .isInstanceOf(RequestException.class)
                .hasMessageContaining("since 不能晚于当前时刻");
    }

    @Test
    void anUnparseableInstantIsRefusedByName() {
        assertThatThrownBy(() -> UsageWindows.parse("2026-11-01", "2026-11-10", null))
                .isInstanceOf(RequestException.class)
                .hasMessageContaining("since 要写成 RFC3339");
    }

    @Test
    void anOverlongSkillKeywordIsRefused() {
        assertThatThrownBy(() -> UsageWindows.parse(null, null, "x".repeat(101)))
                .isInstanceOf(RequestException.class)
                .hasMessageContaining("最多 100");
    }

    @Test
    void aBlankSkillKeywordIsNoFilter() {
        assertThat(UsageWindows.parse(null, null, "   ").skill()).isNull();
    }
}
