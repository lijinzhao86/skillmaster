package com.skillmasterai.modules.token;

import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.TokenValidator;
import com.skillmasterai.modules.token.internal.ClientRegistry;
import com.skillmasterai.modules.token.internal.IssuedTokenValidator;
import com.skillmasterai.modules.token.internal.TokenRevocations;
import com.skillmasterai.modules.token.internal.TokenStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Wires M2.
 *
 * <p>It publishes the module's interfaces and never the classes behind them, following
 * {@code AccountConfiguration}: callers get {@link OAuth2AuthorizationService} and
 * {@link TokenRevocation}, and the store that implements both is a type from {@code internal} they
 * have no way to name. What actually stops them is not javac but {@code ArchitectureTest}.
 *
 * <p><strong>Three of these beans are deliberately the framework's, not ours.</strong> The access
 * token generator, the consent service and the settings are used as shipped: their behaviour does not
 * collide with any decision this project has made, and ADR 0023's judgment is that we write our own
 * only where it does. The consent record in particular is a table we own but do not read or write —
 * handing it to {@code JdbcOAuth2AuthorizationConsentService} is the whole of that decision.
 */
@Configuration(proxyBeanMethods = false)
public class TokenConfiguration {

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcClient jdbc, TokenPolicy policy) {
        return new ClientRegistry(jdbc, policy);
    }

    /**
     * The storage the framework asks for by its own interface.
     *
     * <p>Declared by its concrete type and not aliased to {@link OAuth2AuthorizationService}: a second
     * bean of that type would make "give me the authorization service" ambiguous, and Spring refuses
     * to start rather than picking one. Nothing outside this package names {@code TokenStore}, and
     * {@code ArchitectureTest} is what enforces that.
     */
    @Bean
    TokenStore tokenStore(JdbcClient jdbc, RegisteredClientRepository clients,
            TokenAudience audience, AuditLog audit, TokenPolicy policy) {
        return new TokenStore(jdbc, clients, audience, audit, policy);
    }

    @Bean
    TokenRevocation tokenRevocation(JdbcClient jdbc, AuditLog audit) {
        return new TokenRevocations(jdbc, audit);
    }

    /**
     * The request path's way of turning a token into a subject — M3's seam, implemented here.
     *
     * <p>Declared with M3's interface as its type, so {@code config} can wire it without naming a
     * class from {@code internal}. Nothing else may declare a {@code TokenValidator}: two beans of
     * one type is an ambiguity Spring refuses to resolve, and the failure would be a context that
     * will not start rather than a wrong answer.
     *
     * <p>**It reads the same tables the issuance side writes, in the same process** — which is what
     * makes "same process" a decision rather than a deployment detail (ADR 0007). Validation is a
     * query, not an HTTP introspection call back into ourselves.
     */
    @Bean
    TokenValidator tokenValidator(JdbcClient jdbc, TokenAudience audience) {
        return new IssuedTokenValidator(jdbc, audience);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    /**
     * The framework's composition with the refresh generator replaced.
     *
     * <p>No {@code JwtGenerator}: tokens here are opaque (ADR 0021), so there is no signing key and no
     * {@code /.well-known/jwks.json} to publish. The access-token half is the framework's own, because
     * nothing about an opaque access token collides with a decision we made — it is the refresh half
     * that needs supplying, and only for public clients.
     */
    @Bean
    OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator() {
        return new DelegatingOAuth2TokenGenerator(
                new OAuth2AccessTokenGenerator(),
                new RefreshTokenForPublicClients());
    }
}
