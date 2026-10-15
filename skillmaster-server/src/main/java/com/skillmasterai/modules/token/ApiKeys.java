package com.skillmasterai.modules.token;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * M2's face for the credential an account issues to itself (ADR 0038).
 *
 * <p><strong>Every method is scoped to an owning user, and the scope is a parameter rather than a
 * filter the caller remembers to apply.</strong> A key belongs to one account and nothing else in
 * the system may see it; putting {@code userId} in the signature means a query that forgets it is a
 * compile error rather than a listing that quietly spans accounts. The console answers 404 for
 * "no such key" and for "not yours" alike (the rule {@code SkillNotFound} already follows), so the
 * two are the same absence here.
 *
 * <p><strong>Nothing here decides when a key should expire.</strong> The date arrives as an argument
 * — {@link ApiKeyPolicy} holds the deployment's default and the use case applies it — because "90
 * days" is a product decision and this module is where the number is stored, not where it is chosen.
 */
public interface ApiKeys {

    /**
     * Issues a key and hands back its secret, once.
     *
     * @param scopes    the scopes to store. Signed-and-implied scopes are already folded in by the
     *                  caller (ADR 0039 §决定 2) — the row is the truth, so nothing downstream has to
     *                  know the rule
     * @param expiresAt when it stops working, or null for one that does not expire
     */
    IssuedApiKey issue(String userId, String name, String note, Set<String> scopes,
            Instant expiresAt);

    /**
     * This account's keys, newest first.
     *
     * <p>Revoked ones are included and marked: "I revoked it" and "it was never here" are different
     * answers, and the second one is alarming.
     */
    List<ApiKey> listOf(String userId);

    /** One key, or empty when there is no such key <em>for this account</em>. */
    Optional<ApiKey> find(String userId, String id);

    /**
     * Stops a key working, now.
     *
     * <p>Idempotent and quiet about it: revoking an already-revoked key leaves the earlier timestamp
     * alone, because the question the listing answers is "when did it stop working" and a second
     * click does not change that answer. The caller is expected to have read the key first
     * ({@link #find}) — the console answers 404 for one that is not there, and this method has no
     * opinion about it.
     */
    void revoke(String userId, String id);

    /**
     * Replaces a live key with a newly issued one that continues it.
     *
     * <p>The name, the note and the scopes carry over; the date does not, because what comes out is a
     * brand-new credential and it takes the date a new one takes (ADR 0039 §决定 3). The old row is
     * marked revoked in the same transaction, so there is no window in which both work — a rotation
     * that overlapped would be a period in which nobody is answerable for the extra credential.
     *
     * @param expiresAt the new key's expiry, already computed by the caller
     * @return the new key and its secret, or empty when no <em>live</em> key with that id belongs to
     *         this account — which, after the caller's own read, means it was revoked in between
     */
    Optional<IssuedApiKey> rotate(String userId, String id, Instant expiresAt);
}
