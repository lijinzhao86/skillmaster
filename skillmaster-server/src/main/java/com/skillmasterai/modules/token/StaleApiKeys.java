package com.skillmasterai.modules.token;

import java.time.Instant;

/**
 * M2's system face for the daily "unused for a year" sweep (ADR 0171 §决定 6).
 *
 * <p><strong>Unlike {@link ApiKeys}, this is not scoped to one account.</strong> The caller is the
 * system, and the method only ever touches keys whose last recorded use — or creation, when they
 * have never been used — is older than the threshold. It is a separate type rather than a method on
 * {@code ApiKeys} because that interface's whole invariant is "every method is scoped to an owning
 * user"; a system-wide scan is a different promise, and it should be visible as one.
 */
public interface StaleApiKeys {

    /**
     * Revokes every live key whose last use (or creation, when never used) is older than
     * {@code threshold}, and records one audit row per key.
     *
     * <p>The row is marked, not deleted: the listing has to keep saying that the key existed, and a
     * revoked key that turns up again is the signal ADR 0039 §决定 4 bought.
     *
     * @return how many keys were actually revoked — zero is the ordinary answer
     */
    int revokeUnusedBefore(Instant threshold);
}
