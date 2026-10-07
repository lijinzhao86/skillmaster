package com.skillmasterai.modules.token;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Authenticates the public client on a refresh or a revocation, and hands every other public-client
 * request back to the framework.
 *
 * <p>This <em>replaces</em> the framework's {@code PublicClientAuthenticationProvider} in the list
 * rather than being appended to it, because the framework's copy does not decline a request it cannot
 * handle — it walks on and fails on a {@code code_verifier} a refresh never had. Delegating is what
 * keeps the PKCE check itself the framework's: this class adds a case, it does not reimplement one.
 *
 * <p>What it will not do is widen anything. It answers only "which client is this"; whether the
 * refresh token belongs to that client is still checked further down, where it always was.
 */
public final class PublicClientOnRefreshAndRevocationProvider implements AuthenticationProvider {

    private static final String CODE_VERIFIER = "code_verifier";

    private final RegisteredClientRepository clients;
    private final AuthenticationProvider forPkceRequests;

    public PublicClientOnRefreshAndRevocationProvider(
            RegisteredClientRepository clients, AuthenticationProvider forPkceRequests) {
        this.clients = clients;
        this.forPkceRequests = forPkceRequests;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;

        // A code exchange still carries its verifier; that request is the framework's to judge.
        if (token.getAdditionalParameters().containsKey(CODE_VERIFIER)) {
            return forPkceRequests.authenticate(authentication);
        }

        RegisteredClient client = clients.findByClientId(token.getPrincipal().toString());
        if (client == null
                || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
