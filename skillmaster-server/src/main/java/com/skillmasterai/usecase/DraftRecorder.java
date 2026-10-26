package com.skillmasterai.usecase;

import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records a submission, retrying the whole transaction when a row id collides.
 *
 * <p><strong>Why the retry lives outside the use case's own {@code @Transactional} method.</strong>
 * A primary-key collision aborts the PostgreSQL transaction that hit it, so there is nothing left to
 * retry inside that method: the connection is dirty, the transaction is marked rollback-only, and a
 * second statement would fail for the first one's reason. The only correct unit of retry is therefore
 * a <em>new</em> transaction around the whole submission — validation, namespace resolution, blob
 * writes and the version insert — because the id is generated inside it and a retry must draw a fresh
 * one (v7 TD §digest 与唯一约束).
 *
 * <p><strong>Non-transactional itself, and that is the point.</strong> This class opens the
 * transaction through a template rather than carrying an annotation, so the retry loop sits outside
 * every transaction boundary. The use case method it calls joins the template's transaction
 * ({@code REQUIRED}); a second {@code TransactionTemplate} inside that method would only join the
 * outer one, which is why this cannot be moved inwards.
 *
 * <p><strong>Catching happens outside {@code execute}.</strong> By the time the exception surfaces
 * there the transaction has rolled back and its connection has been released; catching it inside the
 * callback would leave the template believing the work succeeded.
 *
 * <p>Three attempts. One collision is already a 2<sup>-40</sup> event per row; a second in a row is
 * the same event twice, and beyond that the honest answer is a 500 rather than a loop.
 */
@Component
public class DraftRecorder {

    private static final int ATTEMPTS = 3;

    private final TransactionTemplate transactions;

    public DraftRecorder(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * @param submission the whole submission — re-run from the top on a collision
     * @throws DuplicateKeyException when every attempt collided; the caller answers 500
     */
    public <T> T record(Supplier<T> submission) {
        DuplicateKeyException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                return transactions.execute(status -> submission.get());
            } catch (DuplicateKeyException e) {
                // The transaction is rolled back by now; the next attempt starts from nothing.
                last = e;
            }
        }
        throw last;
    }
}
