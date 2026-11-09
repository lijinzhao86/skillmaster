package com.skillmasterai.usecase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the account purge once a day (v56, ADR 0175).
 *
 * <p>The schedule is the {@code UsageRetentionJob} shape — a delay from startup, then every 24
 * hours — for the same reason: the exact hour does not matter and this is the project's one daily
 * housekeeping shape. A failure is logged and retried tomorrow; it must not stop the scheduler or
 * the process.
 *
 * <p><strong>Not in tests.</strong> The purge reads the wall clock while integration tests move
 * {@code deleted_at} into the past by hand; a job running mid-test could purge the fixture the test
 * is about to assert on. The use case is still a bean tests call directly.
 */
@Component
@Profile("!test")
public final class AccountPurgeJob {

    private static final Logger LOG = LoggerFactory.getLogger(AccountPurgeJob.class);

    private final PurgeAccountsUseCase accounts;
    private final AccountRetention retention;

    public AccountPurgeJob(PurgeAccountsUseCase accounts, AccountRetention retention) {
        this.accounts = accounts;
        this.retention = retention;
    }

    @Scheduled(initialDelayString = "PT90S", fixedDelayString = "PT24H")
    public void run() {
        try {
            String cutoff = PurgeAccountsUseCase.cutoff(retention);
            int purged = 0;
            for (String userId : accounts.due(cutoff)) {
                try {
                    accounts.purgeOne(userId);
                    purged++;
                } catch (RuntimeException failure) {
                    // One account's failure is that account's; tomorrow retries it. Rethrowing
                    // would stop the run and leave every later account waiting another day.
                    LOG.error("account purge failed for {}", userId, failure);
                }
            }
            if (purged > 0) {
                LOG.info("account purge: {} account(s) past the {} retention window", purged,
                        retention.personalData());
            }
        } catch (RuntimeException failure) {
            LOG.error("account purge could not list due accounts", failure);
        }
    }
}
