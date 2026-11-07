package com.skillmasterai.usecase.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The scheduled run (ADR 0158): cron, read as Beijing time (the default is in application.yml).
 *
 * <p>There is no run at startup. A deploy therefore does not sync, which keeps a release from
 * adding load whenever it happens; an immediate run is the loopback trigger's job.
 *
 * <p>In-process rather than a separate cron service: the write path already lives here, and a
 * second deployment would need the same database, the same token and the same lock without adding
 * any isolation. One deployment runs one server today; a second instance would duplicate the fetch,
 * not corrupt it, but the guard in {@link SyncRunner} is per-process and the TD says to add a
 * database lock before enabling multi-instance.
 */
public final class SyncJob {

    private static final Logger LOG = LoggerFactory.getLogger(SyncJob.class);

    private final SyncRunner runner;

    public SyncJob(SyncRunner runner) {
        this.runner = runner;
    }

    @Scheduled(cron = "${skillmaster.sync.cron}", zone = "Asia/Shanghai")
    public void run() {
        try {
            SyncReport report = runner.runOnce();
            LOG.info("sync run finished entries={}", report.entries().size());
        } catch (SyncBusyException busy) {
            LOG.info("sync run skipped: already_running");
        } catch (RuntimeException failure) {
            LOG.error("sync run failed", failure);
        }
    }
}
