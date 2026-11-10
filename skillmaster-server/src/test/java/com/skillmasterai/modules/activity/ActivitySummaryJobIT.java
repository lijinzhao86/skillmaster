package com.skillmasterai.modules.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.activity.internal.ActivityRepository;
import com.skillmasterai.support.AbstractIT;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

/**
 * The daily rollup's two jobs besides counting (v58): resuming after a gap, and its own retention.
 *
 * <p>Both were holes a boolean "has any row" left open: a weekend offline would never be filled,
 * and nothing ever deleted a year-old day.
 */
@Sql("/sql/truncate-business-tables.sql")
class ActivitySummaryJobIT extends AbstractIT {

    private static final String ACTOR = "01M3HTG7GCCVBGRPAFFSVSF12W";

    @Autowired
    private ActivitySummaryJob job;

    @Autowired
    private ActivityRepository repository;

    @Test
    void summarizeDayWritesTheSameAbsoluteCountsTwice() {
        String skill = insertSkill();
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        repository.recordInvoke(new InvokeActivity(ACTOR, ACTOR, skill, "demo/summary",
                "version-1", "1.0.0", day + "T10:00:00Z"));
        jdbc.sql("INSERT INTO audit_event (actor_user_id, action, target_type, target_id, at)"
                        + " VALUES (:actor, 'submit', 'skill', :skill, :at)")
                .param("actor", ACTOR).param("skill", skill).param("at", day + "T11:00:00Z")
                .update();

        job.summarizeDay(day);
        job.summarizeDay(day);

        Map<String, Object> counts = jdbc.sql("SELECT invokes, versions FROM skill_collaborator_day"
                        + " WHERE skill_id = :skill AND actor_user_id = :actor AND day = :day")
                .param("skill", skill).param("actor", ACTOR).param("day", day.toString())
                .query()
                .singleRow();
        assertThat(((Number) counts.get("invokes")).longValue()).isEqualTo(1);
        assertThat(((Number) counts.get("versions")).longValue()).isEqualTo(1);
    }

    @Test
    void theFirstRunBackfillsTheWholeDetailWindow() {
        String skill = insertSkill();
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(3);
        ensurePartition(day);
        repository.recordInvoke(new InvokeActivity(ACTOR, ACTOR, skill, "demo/summary",
                "version-1", "1.0.0", day + "T10:00:00Z"));

        job.daily();

        long invokes = jdbc.sql("SELECT invokes FROM skill_collaborator_day"
                        + " WHERE skill_id = :skill AND actor_user_id = :actor AND day = :day")
                .param("skill", skill).param("actor", ACTOR).param("day", day.toString())
                .query(Long.class).single();
        assertThat(invokes).isEqualTo(1);
    }

    @Test
    void dailyDropsTheRollupPastItsRetention() {
        String skill = insertSkill();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        seedRollup(skill, today.minusDays(400), 5);
        seedRollup(skill, today.minusDays(2), 2);

        job.daily();

        assertThat(count("SELECT count(*) FROM skill_collaborator_day WHERE day = :day",
                Map.of("day", today.minusDays(400).toString()))).isZero();
        assertThat(count("SELECT count(*) FROM skill_collaborator_day WHERE day = :day",
                Map.of("day", today.minusDays(2).toString()))).isEqualTo(1);
    }

    private void seedRollup(String skill, LocalDate day, int invokes) {
        jdbc.sql("INSERT INTO skill_collaborator_day"
                        + " (skill_id, actor_user_id, day, invokes, versions, last_at)"
                        + " VALUES (:skill, :actor, :day, :invokes, 0, :at)")
                .param("skill", skill).param("actor", ACTOR).param("day", day.toString())
                .param("invokes", invokes).param("at", day + "T10:00:00Z").update();
    }

    /** The parent has no default partition; a row written three days back needs its week built. */
    private void ensurePartition(LocalDate day) {
        LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String name = "activity_event_" + DateTimeFormatter.BASIC_ISO_DATE.format(monday);
        jdbc.sql("CREATE TABLE IF NOT EXISTS " + name + " PARTITION OF activity_event"
                        + " FOR VALUES FROM ('" + monday + "T00:00:00Z')"
                        + " TO ('" + monday.plusWeeks(1) + "T00:00:00Z')")
                .update();
    }

    private String insertSkill() {
        String id = Ulid.generate();
        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, created_by, created_at, updated_at)
                VALUES (:id, '01M3HTGC79VYJGM8BFXHX2QYNH', :name, '', 'a probe', '{}', 'private',
                        :user, :at, :at)
                """)
                .param("id", id)
                .param("name", "summary-" + id)
                .param("user", ACTOR)
                .param("at", Timestamps.now())
                .update();
        return id;
    }
}
