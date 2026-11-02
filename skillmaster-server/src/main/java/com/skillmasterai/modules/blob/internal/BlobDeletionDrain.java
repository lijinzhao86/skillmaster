package com.skillmasterai.modules.blob.internal;

import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes the OSS objects named by {@code blob_deletion}, outside the request's transaction.
 *
 * <p>Split out of {@link RoutingBlobStore} when that file crossed its size limit; the rule it
 * implements is unchanged — an object is deleted only when no live {@code blob} row points at it,
 * and the check, the delete and the outbox delete are one serialised unit under the same advisory
 * lock an upload takes.
 */
final class BlobDeletionDrain {

    private static final Logger LOG = LoggerFactory.getLogger(BlobDeletionDrain.class);

    /**
     * Runs the sweep where there is no bound transaction.
     *
     * <p>{@code afterCommit} still has the transaction's connection bound, and a statement issued
     * there would join a transaction that will never commit again. A daemon thread has no bound
     * transaction, so its statements autocommit — and the publish request is not held while OSS is
     * slow or down.
     */
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "blob-deletion-drain");
                thread.setDaemon(true);
                return thread;
            });

    private final JdbcClient jdbc;
    private final OssBlobStore oss;
    private final TransactionTemplate transaction;
    private final Consumer<String> lockBlob;

    BlobDeletionDrain(JdbcClient jdbc, OssBlobStore oss,
            PlatformTransactionManager transactions, Consumer<String> lockBlob) {
        this.jdbc = jdbc;
        this.oss = oss;
        this.lockBlob = lockBlob;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Best-effort and at-least-once: a failure leaves the row for the next sweep. */
    void drain() {
        if (oss == null) {
            return;
        }
        List<Deletion> pending = jdbc.sql("""
                        SELECT sha256, location FROM blob_deletion
                        ORDER BY created_at
                        LIMIT 100
                        """)
                .query((rs, rowNum) -> new Deletion(rs.getString("sha256"),
                        rs.getString("location")))
                .list();
        for (Deletion deletion : pending) {
            try {
                transaction.executeWithoutResult(status -> {
                    lockBlob.accept(deletion.sha256());
                    boolean live = jdbc.sql("SELECT 1 FROM blob WHERE sha256 = :sha256")
                            .param("sha256", deletion.sha256())
                            .query(Integer.class)
                            .optional()
                            .isPresent();
                    if (!live) {
                        oss.delete(deletion.location());
                    }
                    jdbc.sql("DELETE FROM blob_deletion WHERE sha256 = :sha256")
                            .param("sha256", deletion.sha256())
                            .update();
                });
            } catch (RuntimeException e) {
                LOG.warn("could not delete OSS object {}; it stays queued", deletion.location(), e);
            }
        }
    }

    /** Runs {@link #drain()} after the caller's transaction commits, or now when there is none. */
    void afterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            WORKER.submit(BlobDeletionDrain.this::drain);
                        }
                    });
            return;
        }
        WORKER.submit(this::drain);
    }

    private record Deletion(String sha256, String location) {
    }
}
