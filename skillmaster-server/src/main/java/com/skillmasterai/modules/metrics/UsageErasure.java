package com.skillmasterai.modules.metrics;

/**
 * Removes one account's usage rows (v56's account purge).
 *
 * <p>Separate from {@link UsageRecorder} and {@link UsageQuery}: erasing is not recording and it is
 * not reading. The rows are per account per day, so they name a person's activity — personal data
 * the privacy policy says goes after the retention window. The daily job would eventually drop them,
 * but that window is ninety days and the promise is thirty.
 */
public interface UsageErasure {

    /** Deletes every daily usage row this account owns. */
    void deleteAllFor(String accountId);
}
