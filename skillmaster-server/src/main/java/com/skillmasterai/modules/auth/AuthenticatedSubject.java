package com.skillmasterai.modules.auth;

import com.skillmasterai.common.Ulid;
import java.util.Set;

/**
 * Who a request is from, once its credential has been accepted.
 *
 * <p>Deliberately minimal: the caller's user id and the scopes the token carries. Nothing
 * about the token itself, and nothing about permissions — whether this subject may read a
 * particular skill is M4's decision, made in the use case, not here. Keeping the two apart is
 * what allows "unauthorized" to be rendered as 404 instead of 403 (see the distribution
 * module) rather than being decided by the security layer.
 *
 * @param userId the ULID of an {@code app_user} row
 * @param scopes the scopes the token grants, e.g. {@code skills:read}
 */
public record AuthenticatedSubject(String userId, Set<String> scopes) {

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
        return new AuthenticatedSubject(userId, Set.of());
    }
}
