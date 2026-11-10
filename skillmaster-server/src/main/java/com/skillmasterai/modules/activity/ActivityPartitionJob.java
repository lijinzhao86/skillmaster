package com.skillmasterai.modules.activity;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Keeps the current and next two weekly partitions present (v58).
 *
 * <p>PostgreSQL has no default partition for {@code activity_event}: an insert with no partition
 * fails. The guarantee therefore belongs to a job that runs at startup and daily, not to the
 * migration — partitions are runtime objects, and a migration only ever builds the parent.
 *
 * <p>The table shape is {@code [Monday 00:00Z, next Monday 00:00Z)}. Weeks rather than days
 * because 90 days online is thirteen partitions, not ninety; and Monday because the boundary is
 * then the start of a UTC week, not midnight in some local zone.
 */
public final class ActivityPartitionJob {

    private static final DateTimeFormatter BOUNDARY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcClient jdbc;

    public ActivityPartitionJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Startup is the first moment every path that writes needs a partition to exist. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ensure();
    }

    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT24H")
    public void daily() {
        ensure();
    }

    public void ensure() {
        LocalDate monday = LocalDate.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (int week = 0; week < 3; week++) {
            create(monday.plusWeeks(week));
        }
    }

    private void create(LocalDate start) {
        LocalDate end = start.plusWeeks(1);
        String name = "activity_event_" + SUFFIX.format(start);
        String sql = "CREATE TABLE IF NOT EXISTS " + name + " PARTITION OF activity_event"
                + " FOR VALUES FROM ('" + BOUNDARY.format(start.atStartOfDay(ZoneOffset.UTC)) + "')"
                + " TO ('" + BOUNDARY.format(end.atStartOfDay(ZoneOffset.UTC)) + "')";
        try {
            jdbc.sql(sql).update();
        } catch (DataAccessException e) {
            if (duplicate(e)) {
                // Two instances can race the same week; the loser's outcome is identical.
                return;
            }
            throw e;
        }
    }

    private static boolean duplicate(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof SQLException sql && "42P07".equals(sql.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
