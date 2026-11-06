package com.skillmasterai.modules.metrics;

import com.skillmasterai.modules.metrics.internal.UsageRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Keeps the deduplicated series bounded. Retention belongs to the scope, not to the skill module:
 * the page reads 30 days, 90 is kept for investigation, and the cumulative series is never purged.
 */
public final class UsageRetentionJob {

    private static final int RETAINED_DAYS = 90;

    private final UsageRepository repository;

    public UsageRetentionJob(UsageRepository repository) {
        this.repository = repository;
    }

    @Scheduled(initialDelayString = "PT45S", fixedDelayString = "PT24H")
    public void purge() {
        repository.deleteBefore(LocalDate.now(ZoneOffset.UTC).minusDays(RETAINED_DAYS),
                UsageScope.PUBLIC);
    }
}
