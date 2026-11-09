package com.skillmasterai.modules.audit;

/**
 * M10's write side.
 *
 * <p>Called explicitly by each use case rather than by an interceptor, and that choice is the
 * point: §3.5's list — publish, delete, rollback, token issue and revoke — is exactly the set of
 * actions that already have a transaction boundary, and an audit row written inside the same
 * transaction as the change it describes cannot disagree with it. An interceptor placed above the
 * use case would have neither the business vocabulary for {@code detail} nor a reliable answer to
 * "did the change actually commit".
 */
@FunctionalInterface
public interface AuditLog {

    void record(AuditEvent event);

    /**
     * Clears the free-form detail from events this person caused (v56's account purge).
     *
     * <p><strong>The event row stays; only its detail goes.</strong> The row is the security
     * record — what happened, to what, when — while {@code detail} is where a handle or a name was
     * written. Deleting the row would erase evidence; keeping the detail for ever would contradict
     * the privacy policy's thirty days. The default is a no-op so a test double need not implement
     * it.
     */
    default void eraseDetailsFor(String userId) {
        // Nothing to erase unless the implementation stores detail.
    }
}
