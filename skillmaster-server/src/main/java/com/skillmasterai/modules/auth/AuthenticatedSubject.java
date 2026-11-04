package com.skillmasterai.modules.auth;

import com.skillmasterai.common.Ulid;
import java.util.Set;

/**
 * Who a request is from, once its credential has been accepted.
 *
 * <p>Deliberately minimal: the caller's user id, the scopes the credential carries, and — when that
 * credential was an API key — which key it was. Nothing about the secret, and nothing about
 * permissions: whether this subject may read a particular skill is M4's decision, made in the use
 * case, not here. Keeping the two apart is what allows "unauthorized" to be rendered as 404 instead
 * of 403 (see the distribution module) rather than being decided by the security layer.
 *
 * @param userId   the ULID of an {@code app_user} row
 * @param scopes   the scopes the credential grants, e.g. {@code skills:read}
 * @param apiKeyId which {@code api_key} row this request is acting as, or <strong>null when the
 *                 credential is not an API key</strong> — an OAuth token, or a browser session.
 *                 <p>An identity rather than a snapshot: the name and the expiry stay in the row, and
 *                 whoever needs them asks M2 — the same shape {@code AccountDirectory} has for a
 *                 handle. Its one reader today is {@code /api/v1/me}, which answers "which key am I
 *                 holding"; that is a reason to keep the rest of the row where it lives, not to copy
 *                 fields into every request's principal, where they would quietly go stale.
 * @param authorizationId which {@code oauth_authorization} row this request is acting through, or
 *                 <strong>null when the credential is not an OAuth token</strong> — an API key, or a
 *                 browser session. It is the identity of "this login" for v46's logout: the token
 *                 row already names it, so the request never has to be told an id, and one credential
 *                 cannot end another's login. Null elsewhere for the same reason as {@code apiKeyId}:
 *                 it is a fact about the credential, not a field somebody forgot.
 */
public record AuthenticatedSubject(String userId, Set<String> scopes, String apiKeyId,
        String authorizationId) {

    public AuthenticatedSubject {
        Ulid.requireValid(userId, "authenticated subject's user id");
        scopes = Set.copyOf(scopes);
    }

    /**
     * The subject a browser session acts as.
     *
     * <p><strong>Empty scopes, because a session presents none.</strong> Scopes are the API plane's
     * mechanism — the token says what it may do, and the filter chain reads that before any of this
     * reaches a use case. The browser plane authenticates a session cookie instead and decides with
     * CSRF, so there is nothing to put here, and inventing a scope would be asserting an
     * authorization that was never asked for. Every use case reached this way reads only
     * {@link #userId()}.
     *
     * <p>A named factory rather than a bare constructor call at each controller, so that the "why is
     * this empty" question is answered once, here, instead of looking like an oversight everywhere
     * it appears.
     */
    public static AuthenticatedSubject ofSession(String userId) {
        return new AuthenticatedSubject(userId, Set.of(), null, null);
    }

    /**
     * The subject an API key acts as (ADR 0038).
     *
     * <p>{@code apiKeyId} is required here and null in every other factory: the caller that has a key
     * id always has one, and a factory that allowed it to be absent would be a way to lose the answer
     * to "which key" while still looking like it had one.
     */
    public static AuthenticatedSubject ofApiKey(String userId, Set<String> scopes, String apiKeyId) {
        if (apiKeyId == null || apiKeyId.isBlank()) {
            throw new IllegalArgumentException("an API key subject must name the key it acts as");
        }
        return new AuthenticatedSubject(userId, scopes, apiKeyId, null);
    }

    /**
     * The subject an OAuth access token acts as — the other credential this plane accepts (ADR 0038
     * keeps it for third-party hosts, and until then for the CLI's transitional login).
     *
     * <p>Named for the same reason the two factories above are: a null {@code apiKeyId} should be
     * read as "this credential is not a key", which is a fact, rather than as a field somebody
     * forgot to fill in.
     *
     * <p>{@code authorizationId} is required here for the same reason: the validator read it from
     * the token's own row, and a subject that lost it could not answer v46's "end the login this
     * credential belongs to".
     */
    public static AuthenticatedSubject ofOAuthToken(String userId, Set<String> scopes,
            String authorizationId) {
        if (authorizationId == null || authorizationId.isBlank()) {
            throw new IllegalArgumentException("an OAuth token subject must name its authorization");
        }
        return new AuthenticatedSubject(userId, scopes, null, authorizationId);
    }
}
