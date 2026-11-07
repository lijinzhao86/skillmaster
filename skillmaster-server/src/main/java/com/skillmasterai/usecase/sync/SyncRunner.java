package com.skillmasterai.usecase.sync;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one gate both entry points share.
 *
 * <p>The scheduled job and the manual trigger must not run at the same time. The guard lives here
 * rather than in either caller: two guards would each answer "not running" and both would start.
 */
public final class SyncRunner {

    private final SyncPipeline pipeline;
    private final AtomicBoolean running = new AtomicBoolean();

    public SyncRunner(SyncPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Runs once.
     *
     * @throws SyncBusyException when another run holds the guard
     */
    public SyncReport runOnce() {
        if (!running.compareAndSet(false, true)) {
            throw new SyncBusyException();
        }
        try {
            return pipeline.run();
        } finally {
            running.set(false);
        }
    }
}
