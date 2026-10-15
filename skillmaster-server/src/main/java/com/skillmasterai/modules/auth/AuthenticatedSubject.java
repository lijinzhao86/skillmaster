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
 */
public record AuthenticatedSubject(String userId, Set<String> scopes, String apiKeyId) {

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
        return new AuthenticatedSubject(userId, Set.of(), null);
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
        return new AuthenticatedSubject(userId, scopes, apiKeyId);
    }

    /**
     * The subject an OAuth access token acts as — the other credential this plane accepts (ADR 0038
     * keeps it for third-party hosts, and until then for the CLI's transitional login).
     *
     * <p>Named for the same reason the two factories above are: a null {@code apiKeyId} should be
     * read as "this credential is not a key", which is a fact, rather than as a field somebody
     * forgot to fill in.
     */
    public static AuthenticatedSubject ofOAuthToken(String userId, Set<String> scopes) {
        return new AuthenticatedSubject(userId, scopes, null);
    }
}
