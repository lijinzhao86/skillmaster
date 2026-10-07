package com.skillmasterai.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one thing this class does, and why it is not cosmetic.
 *
 * <p>Truncating to whole seconds keeps lexicographic order equal to chronological order, and the
 * keyset cursors and the {@code audit_event(at DESC)} index both compare these as strings. With
 * variable-length fractions, {@code …:03.5Z} sorts after {@code …:03.25Z} as text while the instants
 * are the other way round — a page that silently skips or repeats rows.
 */
class TimestampsTest {

    @Test
    void keepsWholeSecondsAndDropsTheFraction() {
        assertThat(Timestamps.format(Instant.parse("2026-10-02T01:02:03.456789Z")))
                .isEqualTo("2026-10-02T01:02:03Z");
        assertThat(Timestamps.format(Instant.parse("2026-10-02T01:02:03Z")))
                .isEqualTo("2026-10-02T01:02:03Z");
    }

    @Test
    void ordersAsTextTheSameWayItOrdersAsInstants() {
        // Ascending as instants, and asserted ascending as strings — including instants less than a
        // second apart, which is where a fraction would show up.
        List<Instant> ascending = List.of(
                Instant.parse("2026-10-02T01:02:03Z"),
                Instant.parse("2026-10-02T01:02:04.5Z"),
                Instant.parse("2026-10-02T01:02:11.25Z"),
                Instant.parse("2026-10-03T01:02:03Z"));

        for (int i = 1; i < ascending.size(); i++) {
            assertThat(Timestamps.format(ascending.get(i)))
                    .as("%s should sort after %s", ascending.get(i), ascending.get(i - 1))
                    .isGreaterThan(Timestamps.format(ascending.get(i - 1)));
        }
    }

    @Test
    void collapsesTwoInstantsInsideTheSameSecond() {
        // The cost of the property above, pinned so it is a decision rather than a discovery: two
        // events 400ms apart are indistinguishable once formatted. Nothing keys on the difference —
        // which is exactly why the column does not need it.
        assertThat(Timestamps.format(Instant.parse("2026-10-02T01:02:03.100Z")))
                .isEqualTo(Timestamps.format(Instant.parse("2026-10-02T01:02:03.900Z")));
    }

    @Test
    void roundTripsThroughText() {
        Instant moment = Instant.parse("2026-10-02T01:02:03.456789Z");

        assertThat(Timestamps.parse(Timestamps.format(moment)))
                .isEqualTo(moment.truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void nowIsTruncatedAsWell() {
        // Someone has to be the one that includes the fraction, and it has to be nobody.
        String now = Timestamps.now();

        assertThat(now).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(Timestamps.parse(now)).isBeforeOrEqualTo(Instant.now());
    }
}
