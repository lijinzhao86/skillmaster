package com.skillmasterai.modules.token;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Recognises a public client on the two requests the framework's own converter passes over.
 *
 * <p>{@code PublicClientAuthenticationConverter} matches only a <em>PKCE</em> token request, so a
 * refresh or a revocation carrying nothing but a {@code client_id} never becomes an
 * {@code Authentication} at all and the endpoint answers {@code invalid_client}. This converter
 * produces that {@code Authentication} for exactly those two shapes.
 *
 * <p><strong>It only claims shapes nobody else handles.</strong> Appended to the framework's list
 * rather than replacing anything, so the PKCE exchange still goes through the framework's converter
 * and provider untouched. A refresh that carries an {@code Authorization} header or a
 * {@code client_secret} is not ours and is declined here — found the hard way, when an earlier
 * version claimed a confidential client's refresh and then refused it for not being public, breaking
 * a path that worked. The rule is ADR 0026's: a coverage may only claim the shape nobody handles.
 */
public final class PublicClientOnRefreshAndRevocationConverter implements AuthenticationConverter {

    private static final String GRANT_TYPE = "grant_type";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String CLIENT_ID = "client_id";
    private static final String CLIENT_SECRET = "client_secret";
    private static final String TOKEN = "token";

    private final String revocationEndpoint;

    public PublicClientOnRefreshAndRevocationConverter(String revocationEndpoint) {
        this.revocationEndpoint = revocationEndpoint;
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        String clientId = request.getParameter(CLIENT_ID);
        if (clientId == null || !(isRefreshGrant(request) || isRevocation(request))) {
            return null;
        }
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null
                || request.getParameter(CLIENT_SECRET) != null) {
            return null;
        }
        // No credentials: a public client has none to give. `client_id` is an identifier here, not a
        // secret, which is what RFC 6749 §6 and RFC 7009 ask for — the refresh token itself is the
        // credential, and the framework checks whose it is one layer down.
        return new OAuth2ClientAuthenticationToken(
                clientId, ClientAuthenticationMethod.NONE, null, Map.of());
    }

    private static boolean isRefreshGrant(HttpServletRequest request) {
        return REFRESH_TOKEN.equals(request.getParameter(GRANT_TYPE))
                && request.getParameter(REFRESH_TOKEN) != null;
    }

    private boolean isRevocation(HttpServletRequest request) {
        return revocationEndpoint.equals(request.getRequestURI())
                && request.getParameter(TOKEN) != null;
    }
}
