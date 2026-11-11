package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes the 下线中 ramps whose date has arrived, once a day (v61, ADR 0184).
 *
 * <p><strong>The sweep is for the notice, not for the state.</strong> Every read derives a due
 * ramp as offline already; this job only writes {@code offline_at} and tells the people who were
 * using the skill. So a missed or late run changes no answer the API gives, and the daily shape is
 * the one the other housekeeping jobs use.
 *
 * <p><strong>One skill at a time, one transaction each.</strong> The completed row and its notice
 * commit together; a failure on one is logged and the next one still runs. The claim inside
 * {@link CompleteDueOfflinesUseCase} is what keeps two instances from both notifying.
 */
@Component
// Not in tests: the sweep reads the wall clock while integration tests move {@code offline_due_at}
// into the past by hand. The use case is a bean tests call directly.
@Profile("!test")
public final class OfflineDueJob {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineDueJob.class);

    private final CompleteDueOfflinesUseCase useCase;

    public OfflineDueJob(CompleteDueOfflinesUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(initialDelayString = "PT120S", fixedDelayString = "PT24H")
    public void run() {
        String now = Timestamps.now();
        int completed = 0;
        for (String skillId : useCase.dueIds(now)) {
            try {
                if (useCase.completeOne(skillId, now)) {
                    completed++;
                }
            } catch (RuntimeException failure) {
                // The next skill still runs: one broken row must not hold the whole ramp queue.
                LOG.warn("offline ramp completion failed for skill {}", skillId, failure);
            }
        }
        if (completed > 0) {
            LOG.info("offline ramps completed: {}", completed);
        }
    }
}
