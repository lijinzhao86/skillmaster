package com.skillmasterai.api;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.activity.UsageHistory;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * The usage range, read off the two planes' query parameters.
 *
 * <p><strong>Instants, not dates.</strong> The client converts "local calendar days" to two
 * instants because only it knows the timezone; this class validates shape and bounds and fills the
 * v58 default (the last 90 days) when no range is given. Both planes call it, so they cannot drift.
 *
 * <p><strong>{@code until} may be up to 26 hours ahead.</strong> A range that ends today is
 * expressed as "next local midnight", which is in the future; rejecting it would reject every
 * range ending today. Rows in the future cannot exist, so the query is harmless — the tolerance
 * only stops a client from asking for an arbitrary far-future window.
 */
final class UsageWindows {

    /** One day of slack for a timezone, on top of the boundary itself. */
    private static final Duration FUTURE_TOLERANCE = Duration.ofHours(26);

    /** 90 days plus DST slack: a 90-day local range is 90 days ± 1 hour. */
    private static final Duration MAX_SPAN = Duration.ofDays(UsageHistory.WINDOW_DAYS).plusHours(2);

    private static final int MAX_SKILL_LENGTH = 100;

    private UsageWindows() {
    }

    static UsageHistory.Window parse(String since, String until, String skill) {
        return parse(since, until, skill, Instant.now());
    }

    /**
     * The same parse against a supplied clock. Tests pin the instant so a default window can be
     * asserted exactly; production always passes {@link Instant#now()}.
     */
    static UsageHistory.Window parse(String since, String until, String skill, Instant now) {
        boolean hasSince = since != null && !since.isBlank();
        boolean hasUntil = until != null && !until.isBlank();
        if (hasSince != hasUntil) {
            throw invalid("since 与 until 要一起给。");
        }
        if (!hasSince) {
            // `until` one second ahead: the window is half-open, and a row written in this same
            // second must still be readable by the request that follows it.
            return new UsageHistory.Window(
                    Timestamps.format(now.minus(UsageHistory.WINDOW_DAYS, ChronoUnit.DAYS)),
                    Timestamps.format(now.plusSeconds(1)), skill(skill));
        }
        Instant start = instant(since, "since");
        Instant end = instant(until, "until");
        if (end.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw invalid("until 不能晚于今天。");
        }
        if (!end.isAfter(start)) {
            throw invalid("until 要晚于 since。");
        }
        if (start.isAfter(now)) {
            throw invalid("since 不能晚于当前时刻。");
        }
        if (Duration.between(start, end).compareTo(MAX_SPAN) > 0) {
            throw invalid("时间跨度最多 90 天。");
        }
        if (start.isBefore(now.minus(MAX_SPAN))) {
            throw invalid("只能看最近 90 天。");
        }
        return new UsageHistory.Window(Timestamps.format(start), Timestamps.format(end),
                skill(skill));
    }

    private static Instant instant(String value, String name) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(name + " 要写成 RFC3339 时刻。");
        }
    }

    private static String skill(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_SKILL_LENGTH) {
            throw invalid("skill 关键词最多 " + MAX_SKILL_LENGTH + " 个字符。");
        }
        return trimmed;
    }

    private static RequestException invalid(String message) {
        return new RequestException(ErrorCode.INVALID_REQUEST, message);
    }
}
