package com.skillmasterai.usecase;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the unused-key sweep once a day (ADR 0171 §决定 6).
 *
 * <p>The schedule is the {@code UsageRetentionJob} shape — a delay from startup, then every 24
 * hours — rather than a cron: the exact hour does not matter, and a fixed delay is the one this
 * project already uses for daily housekeeping. A failure is logged and retried tomorrow; it must
 * not stop the scheduler or the process.
 */
@Component
// **Not in tests.** The sweep reads the wall clock, while the integration tests move `created_at`
// into the past by hand; once the calendar catches up with those fixtures the job could revoke a row
// in the middle of a test. The use case is still a bean tests call directly.
@Profile("!test")
public final class RevokeUnusedApiKeysJob {

    private static final Logger LOG = LoggerFactory.getLogger(RevokeUnusedApiKeysJob.class);

    private final RevokeUnusedApiKeysUseCase useCase;

    public RevokeUnusedApiKeysJob(RevokeUnusedApiKeysUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(initialDelayString = "PT60S", fixedDelayString = "PT24H")
    public void run() {
        try {
            int revoked = useCase.revokeUnused(Instant.now());
            if (revoked > 0) {
                LOG.info("api key auto revoke: {} key(s) unused for {} days", revoked,
                        RevokeUnusedApiKeysUseCase.UNUSED_FOR.toDays());
            }
        } catch (RuntimeException failure) {
            LOG.error("api key auto revoke failed", failure);
        }
    }
}
