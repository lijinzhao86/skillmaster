package com.skillmasterai.modules.account;

import java.util.List;

/**
 * What an account deletion does to M1's own rows (v56, ADR 0175).
 *
 * <p><strong>The request and the purge are two different moments, and only the second one belongs
 * here alone.</strong> Deletion takes effect immediately — the account can no longer act — but its
 * personal data and the content in its personal namespace are kept for the retention window, so a
 * mistake has something to restore from the backups for. {@link #markDeleted} is the first moment;
 * {@link #purgePersonalData} is the second, run by the daily job once the window has passed.
 *
 * <p>Every method is called inside a transaction by the use case, like {@link AccountRegistrar}'s.
 */
public interface AccountDeletion {

    /**
     * Stops the account acting, now, and records when the window opened.
     *
     * <p>{@code status} becomes {@code deleted}, which {@link AccountDirectory#isActive} already
     * refuses; {@code deleted_at} is what the purge reads. Does not touch the credentials, the
     * namespace or anybody's content — those are the purge's and other modules'.
     *
     * @return whether this call performed the transition. <strong>False means the account was
     *         already deleted</strong> — a second request from another tab, or a double click —
     *         and the caller must not repeat the side effects (notices would duplicate).
     */
    boolean markDeleted(String userId, String at);

    /**
     * Accounts whose deletion window ended at or before {@code cutoff}, oldest first.
     *
     * @param cutoff RFC3339, the same shape {@code app_user.deleted_at} is stored in
     */
    List<String> dueForPurge(String cutoff);

    /**
     * Deletes M1's personal rows and de-identifies the account row, which is <strong>kept</strong>.
     *
     * <p>Rows removed: the password, the consent record, and every verification code sent to this
     * account's phone. The {@code app_user} row loses its phone columns and its handle, and its
     * display name becomes 「已注销账号」 — other people's content and audit rows still point at its
     * id, and deleting the row would erase somebody else's history (ADR 0175 §决定 3). This is
     * de-identification, not anonymisation, and the privacy policy says exactly that.
     */
    void purgePersonalData(String userId);

    /**
     * Claims this account for the purge, so two instances cannot purge it at once.
     *
     * <p>The daily job runs on every instance; without a claim, two of them would both list the
     * same due account, both take the global blob-sweep lock and both write an audit row. The
     * conditional update is the lock: exactly one caller moves {@code purged_at} from null.
     *
     * @return whether this caller now owns the purge
     */
    boolean claimForPurge(String userId, String at);

    /** Whether this account's status is already {@code deleted} — the three-state answer. */
    boolean isDeleted(String userId);
}
