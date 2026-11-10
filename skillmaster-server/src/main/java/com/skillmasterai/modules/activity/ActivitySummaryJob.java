package com.skillmasterai.modules.activity;

import com.skillmasterai.modules.activity.internal.ActivityRepository;
import com.skillmasterai.modules.audit.AuditCounts;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Builds the owner view's daily table from the two ledgers (v58).
 *
 * <p>The detail log answers "what did this person do"; the owner's page asks "who uses this skill,
 * and how much". Answering that by scanning 90 days of detail on every page load would put the
 * log's growth into the page's latency. So the job reads yesterday once, writes one row per
 * skill × person × day, and the page reads a nine-row-per-person table.
 *
 * <p>Both inputs are append-only, so the upserts are absolute counts: re-running a day replaces
 * that day's number rather than adding to it. That is what makes the job safe to run twice.
 *
 * <p><strong>The watermark, not a boolean.</strong> "Has any row" was the first shape and it has a
 * hole: a weekend offline, or a crash halfway through the first fill, leaves rows behind and every
 * later run then believes only yesterday is missing. The newest day already written is the point to
 * resume from, and the loop is bounded by the detail window so a gap older than 90 days is never
 * re-derived (the detail rows it would need have already been archived).
 */
public final class ActivitySummaryJob {

    private static final int RETAINED_DAYS = UsageHistory.WINDOW_DAYS;

    /** ADR 0179 决定 4: the rollup is the warm layer, kept a year. */
    private static final int ROLLUP_RETAINED_DAYS = 365;

    private final ActivityRepository repository;
    private final AuditCounts auditCounts;

    public ActivitySummaryJob(ActivityRepository repository, AuditCounts auditCounts) {
        this.repository = repository;
        this.auditCounts = auditCounts;
    }

    /**
     * Starts 90 seconds after readiness — longer than any deployment probe, and short enough that
     * the owner's page is not empty for a whole day after the first deploy.
     */
    @Scheduled(initialDelayString = "PT90S", fixedDelayString = "PT24H")
    public void daily() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate yesterday = today.minusDays(1);
        LocalDate oldest = today.minusDays(RETAINED_DAYS);

        Optional<String> latest = repository.latestSummaryDay();
        // **Recompute the newest written day, do not start after it.** The upserts are absolute,
        // so re-running is free; starting at `max(day) + 1` would leave the rest of a day that was
        // half-written when the process died missing forever.
        LocalDate from = latest.map(LocalDate::parse).orElse(oldest);
        if (from.isBefore(oldest)) {
            from = oldest;
        }
        for (LocalDate day = from; !day.isAfter(yesterday); day = day.plusDays(1)) {
            summarizeDay(day);
        }
        repository.deleteSummaryBefore(today.minusDays(ROLLUP_RETAINED_DAYS).toString());
    }

    /** One day's absolute counts from both ledgers. Public for the tests that pin a fixed day. */
    public void summarizeDay(LocalDate day) {
        String from = day + "T00:00:00Z";
        String until = day.plusDays(1) + "T00:00:00Z";

        for (ActivityRepository.InvokeDay row : repository.invokeDays(from, until)) {
            repository.upsertInvokes(row);
        }
        for (AuditCounts.Submission row : auditCounts.submissions(from, until)) {
            repository.upsertVersions(row.skillId(), row.actorUserId(), row.day(), row.versions(),
                    row.lastAt());
        }
    }
}
