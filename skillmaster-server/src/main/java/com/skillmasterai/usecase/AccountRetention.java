package com.skillmasterai.usecase;

import java.time.Duration;

/**
 * How long a deleted account's personal data and content are kept before the purge removes them
 * (v56, ADR 0175).
 *
 * <p>Configuration rather than a constant for one practical reason: the integration tests set it to
 * seconds so the purge can be exercised without waiting a month. The production value and the
 * privacy policy's sentence are the same number, and the release checklist checks them together.
 *
 * @param personalData the window; the account cannot act during it, and the content is already
 *                     invisible — this is only what a backup could still restore
 */
public record AccountRetention(Duration personalData) {
}
