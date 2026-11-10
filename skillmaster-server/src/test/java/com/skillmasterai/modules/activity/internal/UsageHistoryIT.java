package com.skillmasterai.modules.activity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.activity.InvokeActivity;
import com.skillmasterai.modules.activity.SearchActivity;
import com.skillmasterai.modules.activity.SearchHit;
import com.skillmasterai.modules.activity.UsageHistory;
import com.skillmasterai.support.AbstractIT;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

/**
 * The page and its stats over the same rows (v58 A1/A3/A18): a search carries the calls it
 * produced, a direct call is its own row, and the four numbers are computed from the log rather
 * than stored beside it.
 */
@Sql("/sql/truncate-business-tables.sql")
class UsageHistoryIT extends AbstractIT {

    private static final String ACTOR = "01M3HTG7GCCVBGRPAFFSVSF12W";

    @Autowired
    private ActivityRepository repository;

    @Autowired
    private PgActivity activity;

    private Instant now;

    @BeforeEach
    void seed() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        repository.recordSearch(new SearchActivity(ACTOR, "pdf", Map.of("scope", "public"), 2L,
                List.of(new SearchHit("skill-a", "demo/a"),
                        new SearchHit("skill-b", "demo/b")), false, ago(120)));
        repository.recordInvoke(new InvokeActivity(ACTOR, ACTOR, "skill-a", "demo/a", "v1",
                "1.0.0", ago(110)));
        repository.recordInvoke(new InvokeActivity(ACTOR, ACTOR, "skill-c", "demo/c", "v2",
                "2.0.0", ago(100)));
        repository.recordSearch(new SearchActivity(ACTOR, "nothing", Map.of("scope", "public"),
                0L, List.of(), false, ago(90)));
    }

    @Test
    void aPageShowsSearchesWithTheirCallsAndDirectCallsBesideThem() {
        UsageHistory.Page page = activity.interactions(ACTOR, 1, 50, nowAt());

        assertThat(page.total()).as("two searches + one direct call").isEqualTo(3);
        assertThat(page.items()).hasSize(3);
        assertThat(page.items().get(0)).isInstanceOf(UsageHistory.Search.class);
        assertThat(((UsageHistory.Search) page.items().get(0)).query()).isEqualTo("nothing");
        assertThat(((UsageHistory.Search) page.items().get(0)).results()).isEmpty();

        UsageHistory.Direct direct = (UsageHistory.Direct) page.items().get(1);
        assertThat(direct.skill()).isEqualTo("demo/c");

        UsageHistory.Search first = (UsageHistory.Search) page.items().get(2);
        assertThat(first.results()).extracting(UsageHistory.Result::rank).containsExactly(1, 2);
        assertThat(first.invocations()).extracting(UsageHistory.Invocation::skill)
                .containsExactly("demo/a");
    }

    @Test
    void theStatsAreTheSameRowsCounted() {
        UsageHistory.Stats stats = activity.stats(ACTOR, nowAt());

        assertThat(stats.calls()).isEqualTo(2);
        assertThat(stats.searches()).isEqualTo(2);
        assertThat(stats.converted()).isEqualTo(1);
        assertThat(stats.missed()).isEqualTo(1);
        assertThat(stats.skills()).isEqualTo(2);
        assertThat(stats.top()).extracting(UsageHistory.TopSkill::skill)
                .containsExactlyInAnyOrder("demo/a", "demo/c");
    }

    @Test
    void paginationDoesNotRepeatOrLoseRows() {
        UsageHistory.Page first = activity.interactions(ACTOR, 1, 2, nowAt());
        UsageHistory.Page second = activity.interactions(ACTOR, 2, 2, nowAt());

        assertThat(first.items()).hasSize(2);
        assertThat(second.items()).hasSize(1);
        assertThat(first.total()).isEqualTo(3);
    }

    @Test
    void theWindowIsMeasuredFromTheQueryInstant() {
        // 90 days before this instant is 105 minutes ago: only the newest two rows are inside.
        String at = Timestamps.format(now.plus(90, ChronoUnit.DAYS).minus(105, ChronoUnit.MINUTES));
        UsageHistory.Page page = activity.interactions(ACTOR, 1, 50, at);
        UsageHistory.Stats stats = activity.stats(ACTOR, at);

        assertThat(page.total()).isEqualTo(2);
        assertThat(stats.searches()).isEqualTo(1);
        assertThat(stats.calls()).isEqualTo(1);
    }

    @Test
    void aLongHistoryPagesWithoutRepeatingOrLosingRows() {
        String actor = "01M3HTG7GCCVBGRPAFFSVSF12X";
        for (int i = 0; i < 120; i++) {
            repository.recordSearch(new SearchActivity(actor, "q" + i, Map.of(), 1L,
                    List.of(new SearchHit("skill", "demo/skill")), false,
                    ago(120 + i)));
        }

        UsageHistory.Page first = activity.interactions(actor, 1, 50, nowAt());
        UsageHistory.Page third = activity.interactions(actor, 3, 50, nowAt());
        UsageHistory.Page fourth = activity.interactions(actor, 4, 50, nowAt());

        assertThat(first.total()).isEqualTo(120);
        assertThat(first.pageSize()).isEqualTo(50);
        assertThat(first.items()).hasSize(50);
        assertThat(third.items()).hasSize(20);
        assertThat(fourth.items()).isEmpty();
        assertThat(fourth.page()).isEqualTo(4);
        assertThat(first.items()).doesNotContainAnyElementsOf(third.items());
    }

    @Test
    void theWindowKeepsTheNinetyDayBoundaryInclusive() {
        String actor = "01M3HTG7GCCVBGRPAFFSVSF12Y";
        ensurePartitionAt(now.minus(91, ChronoUnit.DAYS));
        ensurePartitionAt(now);
        repository.recordSearch(new SearchActivity(actor, "eighty-nine", Map.of(), 1L,
                List.of(new SearchHit("skill", "demo/skill")), false,
                atDaysAgo(89)));
        repository.recordSearch(new SearchActivity(actor, "ninety", Map.of(), 1L,
                List.of(new SearchHit("skill", "demo/skill")), false,
                atDaysAgo(90)));
        repository.recordSearch(new SearchActivity(actor, "ninety-one", Map.of(), 1L,
                List.of(new SearchHit("skill", "demo/skill")), false,
                atDaysAgo(91)));

        UsageHistory.Page page = activity.interactions(actor, 1, 50, nowAt());
        UsageHistory.Stats stats = activity.stats(actor, nowAt());

        assertThat(page.items()).extracting(item -> ((UsageHistory.Search) item).query())
                .containsExactly("eighty-nine", "ninety");
        assertThat(stats.searches()).isEqualTo(2);
    }

    /**
     * The parent table has no default partition, and the job only keeps this week onward. A test
     * that writes history therefore has to build the week it needs first.
     */
    private void ensurePartitionAt(Instant instant) {
        LocalDate monday = LocalDate.ofInstant(instant, ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String name = "activity_event_" + DateTimeFormatter.BASIC_ISO_DATE.format(monday);
        jdbc.sql("CREATE TABLE IF NOT EXISTS " + name + " PARTITION OF activity_event"
                        + " FOR VALUES FROM ('" + monday + "T00:00:00Z')"
                        + " TO ('" + monday.plusWeeks(1) + "T00:00:00Z')")
                .update();
    }

    @Test
    void aCallWhoseSearchLeftTheWindowIsDirectAndNotConverted() {
        String actor = "01M3HTG7GCCVBGRPAFFSVSF12Z";
        Instant searchAt = now.minus(90, ChronoUnit.DAYS).minus(10, ChronoUnit.MINUTES);
        Instant callAt = now.minus(90, ChronoUnit.DAYS).plus(5, ChronoUnit.MINUTES);
        ensurePartitionAt(searchAt);
        ensurePartitionAt(callAt);
        repository.recordSearch(new SearchActivity(actor, "stale", Map.of(), 1L,
                List.of(new SearchHit("skill-stale", "demo/stale")), false,
                Timestamps.format(searchAt)));
        repository.recordInvoke(new InvokeActivity(actor, actor, "skill-stale", "demo/stale",
                "v1", "1.0.0", Timestamps.format(callAt)));

        UsageHistory.Page page = activity.interactions(actor, 1, 50, nowAt());
        UsageHistory.Stats stats = activity.stats(actor, nowAt());

        // The search is 10 minutes older than the window and the call it produced is 15 minutes
        // younger, inside it. It is shown as a direct call, and cannot count as converted.
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0)).isInstanceOf(UsageHistory.Direct.class);
        assertThat(stats.calls()).isEqualTo(1);
        assertThat(stats.searches()).isZero();
        assertThat(stats.converted()).isZero();
        assertThat(stats.missed()).isZero();
    }

    private String atDaysAgo(int days) {
        return Timestamps.format(now.minus(days, ChronoUnit.DAYS));
    }

    private String nowAt() {
        return Timestamps.format(now);
    }

    private String ago(int minutes) {
        return Timestamps.format(now.minus(minutes, ChronoUnit.MINUTES));
    }
}
