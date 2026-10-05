package com.skillmasterai.modules.token;

/**
 * Ends every credential a person holds, in one act.
 *
 * <p>One of M2's two exits. It exists as an interface with a plain-{@code String} parameter for the
 * reason §2.5 gives: the caller is the use-case layer, and a signature that named one of M2's own
 * types would put a module type in the orchestration that is supposed to sit above all of them.
 *
 * <p><strong>Its callers are the moments a person's access must end at once</strong> — a password
 * reset, and a suspension. Both are use cases that already change one row for M1; the revocation is
 * the second half of the same transaction, not a follow-up. A suspension that only wrote
 * {@code status = 'suspended'} would leave every token the user holds working, because M1 checks
 * that column at login and nowhere else.
 *
 * <p>It is also the mechanism behind v1's "sign out everywhere" — which the design deliberately does
 * not expose as a button. The capability is here; only the interface is deferred.
 */
public interface TokenRevocation {

    /**
     * Marks every live token that acts as this user as revoked, across every client and every
     * authorization.
     *
     * <p>Idempotent, and silent about whether there was anything to revoke: a user with no tokens is
     * not an error, and a caller that had to check first would be reading M2's tables.
     *
     * @param userId the ULID of an {@code app_user} row — the same value tokens carry as {@code sub}
     */
    void revokeAllFor(String userId);
}
