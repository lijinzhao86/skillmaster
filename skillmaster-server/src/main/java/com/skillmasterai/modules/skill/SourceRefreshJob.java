package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.SourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Fills the gaps a successful read did not: a manual upload's version starts pending at publish,
 * and a read that failed leaves it pending, error or stale.
 *
 * <p>A snapshot that already says {@code ok} is not re-read because time passed (ADR 0161): the
 * numbers belong to the commit the version came from, and refreshing them on a schedule is a
 * separate, weekly job a later version owns. This task therefore never touches a settled snapshot,
 * however old it is.
 *
 * <p>One deployment runs one server today, so no distributed lock is needed; a second instance
 * would duplicate the fetch, not corrupt it (the upsert is idempotent).
 */
public final class SourceRefreshJob {

    private static final Logger LOG = LoggerFactory.getLogger(SourceRefreshJob.class);

    private final SourceRepository repository;
    private final SourceService sources;

    public SourceRefreshJob(SourceRepository repository, SourceService sources) {
        this.repository = repository;
        this.sources = sources;
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT24H")
    public void refresh() {
        for (SourceRepository.Pending pending : repository.pending()) {
            try {
                sources.refresh(pending.skillId(), pending.versionId());
            } catch (RuntimeException failure) {
                /* One broken source must not stop the rest; refresh() already stores fetch failures.
                   A row that cannot even resolve its adapter writes no status, so without this line
                   it would be retried silently every day. */
                LOG.warn("source refresh failed skill={} version={}", pending.skillId(),
                        pending.versionId(), failure);
            }
        }
    }
}
