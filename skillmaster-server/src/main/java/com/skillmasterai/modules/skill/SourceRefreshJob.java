package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.SourceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Keeps the source snapshots fresh without putting a third party on the read path.
 *
 * <p>One deployment runs one server today, so no distributed lock is needed; a second instance
 * would duplicate the fetch, not corrupt it (the upsert is idempotent).
 */
public final class SourceRefreshJob {

    private final SourceRepository repository;
    private final SourceService sources;

    public SourceRefreshJob(SourceRepository repository, SourceService sources) {
        this.repository = repository;
        this.sources = sources;
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT24H")
    public void refresh() {
        String cutoff = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        for (SourceRepository.Pending pending : repository.pending(cutoff)) {
            try {
                sources.refresh(pending.skillId(), pending.versionId());
            } catch (RuntimeException ignored) {
                // One broken source must not stop the rest; refresh() already stores fetch failures.
            }
        }
    }
}
