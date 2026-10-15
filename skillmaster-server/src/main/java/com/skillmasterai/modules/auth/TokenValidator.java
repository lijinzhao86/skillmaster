package com.skillmasterai.modules.auth;

/**
 * Turns a presented bearer token into a subject, or names the refusal.
 *
 * <p>This is M3's seam, and it exists from v1 on purpose. P0 resolved a single static token from
 * configuration; P1 replaced that with tokens the authorization server signs and refreshes; v2 adds
 * the second kind of credential beside them (ADR 0038). Nothing above this interface may know which
 * of them is in play — otherwise each new credential becomes a rewrite of the request path instead
 * of a second implementation, which is the same argument §4.4 makes about client lookup.
 *
 * <p><strong>One implementation decides, and it is the one that has to look at the row anyway.</strong>
 * The precedence between "revoked", "suspended" and "expired" is a property of that implementation,
 * not of this interface: a caller that tried to re-derive it from the outside would be a second
 * opinion about the same fact, and the two would drift the first time a rule changed.
 *
 * <p>The contract's boundary — "not ours" stays indistinguishable from "nothing presented", while
 * "ours and not usable" says why — is written out on {@link TokenValidation}, which is where the
 * reasoning belongs because that is the type that carries the answer.
 */
@FunctionalInterface
public interface TokenValidator {

    /**
     * @param presentedToken  the credential as presented. Never stored, never logged — it is hashed
     *                        and the hash is what is looked up (ADR 0007)
     * @param callerAddress   where the request came from, as the servlet sees it after the container
     *                        has applied the forwarded-header rules (§4.1, ADR 0018). Used only to
     *                        notice a credential being used from somewhere new, and only for
     *                        credentials that outlive a single request. Null when there is no caller
     *                        to speak of — a test, or a path with no socket behind it
     */
    TokenValidation validate(String presentedToken, String callerAddress);
}
