package com.skillmasterai.modules.auth;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * The identity a browser session authenticates as: a user id, and nothing else.
 *
 * <p>Carries no authorities. There is nothing on this plane to authorize — every endpoint is
 * either public or open to anyone signed in — and minting scopes for it would put a second
 * permission model beside the bearer plane's.
 *
 * <p><strong>The principal is the bare user id, and {@link #getName()} returns it.</strong> Both
 * are load-bearing. Spring Session indexes every session by the principal name, which is what makes
 * "end all of this user's sessions" a lookup rather than a scan; and the authorization server of
 * §4.4 will put this name in the {@code sub} claim of the tokens it issues. Nothing in this module
 * would fail if the principal were a wrapper object or the name were the username — the login
 * would still work, and password reset would silently stop revoking anything.
 */
public final class WebAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final String userId;

    public WebAuthentication(String userId) {
        super(List.of());
        this.userId = userId;
        setAuthenticated(true);
    }

    /** Null: the password proved identity at login and has no use after it. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }

    /**
     * Stated rather than inherited. {@code AbstractAuthenticationToken} would fall back to the
     * principal and reach the same string today, but this is a contract two other components read,
     * so it is written down where a change to it would have to be deliberate.
     */
    @Override
    public String getName() {
        return userId;
    }
}
