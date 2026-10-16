package com.skillmasterai.config;

import static org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames.DPOP_SIGNING_ALG_VALUES_SUPPORTED;
import static org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames.INTROSPECTION_ENDPOINT;
import static org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames.INTROSPECTION_ENDPOINT_AUTH_METHODS_SUPPORTED;
import static org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames.JWKS_URI;
import static org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames.TLS_CLIENT_CERTIFICATE_BOUND_ACCESS_TOKENS;

import com.skillmasterai.modules.token.PublicClientOnRefreshAndRevocationConverter;
import com.skillmasterai.modules.token.PublicClientOnRefreshAndRevocationProvider;
import com.skillmasterai.modules.token.TokenAudience;
import com.skillmasterai.modules.token.TokenPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadata;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.PublicClientAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * The third chain: the token plane.
 *
 * <p>M2's HTTP surface is a plane of its own, beside the browser's and the API's, because its rules
 * are neither. A token request authenticates a <em>client</em> and carries no user session; an
 * authorization request is the reverse — it needs the browser session M1 established and knows
 * nothing about clients' secrets. Folding these into either existing chain would mean loosening that
 * chain's rule for paths that are not its own.
 *
 * <p><strong>The path matcher is a startup condition, not tidiness.</strong> Without it this chain
 * claims every request, and Spring Security 7 refuses to start a second chain that could then never
 * be reached ({@code UnreachableFilterChainException}). The spike hit exactly this, which is a decent
 * demonstration that the framework would rather fail loudly than let the first chain quietly win.
 *
 * <p><strong>Order 0, above the browser chain,</strong> because the three matchers are disjoint and
 * the order only has to make that obvious; the narrower plane goes first so no chain needs a negation.
 */
@Configuration(proxyBeanMethods = false)
class TokenSecurityConfig {

    /**
     * Where the framework sends a browser that has not consented yet.
     *
     * <p>A route of the SPA, not a page this server renders — the same division as {@code /login}
     * (ADR 0015). The framework only builds the redirect; nothing here serves it.
     */
    private static final String CONSENT_PAGE = "/consent";

    /** M1's browser login. The authorization endpoint redirects here when nobody is signed in. */
    private static final String LOGIN_PAGE = "/login";

    /**
     * What this server calls itself, and how long its tokens live, from configuration.
     *
     * <p>The paths are set to {@code /oauth/*} deliberately rather than left at the framework's
     * {@code /oauth2/*} defaults: §4.4 publishes this shape, and clients read it from the metadata
     * document rather than hardcoding it — but the document has to say the true path either way.
     *
     * <p>The issuer is the public base URL, and that is the only setting here that could not be
     * defaulted: left unset the framework derives it from the request, which behind a proxy or a
     * health check produces whatever host that request arrived on. The minted tokens would still
     * verify, and the metadata document would be wrong — the sort of failure that only shows up in a
     * client that trusted it.
     */
    @Bean
    AuthorizationServerSettings authorizationServerSettings(SkillmasterProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.publicBaseUrl())
                .authorizationEndpoint("/oauth/authorize")
                .tokenEndpoint("/oauth/token")
                .tokenRevocationEndpoint("/oauth/revoke")
                .build();
    }

    /** The audience tokens are minted for. The same value as the issuer, said once, in one place. */
    @Bean
    TokenAudience tokenAudience(SkillmasterProperties properties) {
        return new TokenAudience(properties.publicBaseUrl());
    }

    /**
     * How long tokens live, turned from configuration into the value M2 takes.
     *
     * <p>Built here because a module may not read configuration (the layering rule, same as M1's
     * cipher keys). {@link TokenPolicy}'s constructor is what validates, so a deployment that set a
     * ceiling below its idle lifetime finds out at startup rather than by watching one of its two
     * rulers never fire.
     */
    @Bean
    TokenPolicy tokenPolicy(SkillmasterProperties properties) {
        SkillmasterProperties.Tokens tokens = properties.tokens();
        return new TokenPolicy(
                tokens.accessToken(),
                tokens.refreshTokenIdle(),
                tokens.refreshTokenAbsolute(),
                tokens.authorizationCode(),
                tokens.refreshReplayGrace());
    }

    @Bean
    @Order(0)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            OAuth2AuthorizationService authorizations,
            RegisteredClientRepository clients,
            OAuth2AuthorizationConsentService consents,
            OAuth2TokenGenerator<? extends OAuth2Token> tokens,
            AuthorizationServerSettings settings,
            SecurityContextRepository contexts,
            CsrfTokenRepository csrfTokens) throws Exception {
        http
                .securityMatcher("/oauth/**", "/.well-known/**")
                .oauth2AuthorizationServer(authorizationServer -> authorizationServer
                        .authorizationService(authorizations)
                        .registeredClientRepository(clients)
                        .authorizationConsentService(consents)
                        .tokenGenerator(tokens)
                        .authorizationServerSettings(settings)
                        .clientAuthentication(clientAuthentication -> clientAuthentication
                                .authenticationConverter(
                                        new PublicClientOnRefreshAndRevocationConverter(
                                                settings.getTokenRevocationEndpoint()))
                                .authenticationProviders(providers -> {
                                    AuthenticationProvider forPkce = providers.stream()
                                            .filter(PublicClientAuthenticationProvider.class::isInstance)
                                            .findFirst()
                                            .orElseThrow(() -> new IllegalStateException(
                                                    "the framework's public-client provider is no longer"
                                                            + " registered; this override must be revisited"
                                                            + " before it can be trusted"));
                                    providers.remove(forPkce);
                                    providers.add(new PublicClientOnRefreshAndRevocationProvider(
                                            clients, forPkce));
                                }))
                        .authorizationEndpoint(endpoint -> endpoint.consentPage(CONSENT_PAGE))
                        .authorizationServerMetadataEndpoint(metadata -> metadata
                                .authorizationServerMetadataCustomizer(
                                        TokenSecurityConfig::describeThisServer)))
                .authorizeHttpRequests(requests -> requests
                        // The two endpoints a client calls with no user present. They are not open:
                        // each authenticates its client inside the endpoint's own filter, which is what
                        // `authenticated()` here would have prevented — it demands a *user*, and a CLI
                        // exchanging a code has none. Left as they are, they answer invalid_client.
                        .requestMatchers("/oauth/token", "/oauth/revoke").permitAll()
                        // Discovery has to be readable by a machine that has never authenticated —
                        // that is its entire purpose (RFC 9728, RFC 8414). Requiring anything here
                        // makes the document unreachable by exactly the clients it exists for.
                        .requestMatchers("/.well-known/**").permitAll()
                        // /oauth/authorize is the one that needs a person: it decides consent, and
                        // "who is this" comes from M1's browser session (ADR 0014).
                        .anyRequest().authenticated())
                // Left on, with the browser chain's repository and handler, but **it does not cover
                // the consent submission** — and that is worth stating rather than leaving for
                // somebody to discover. The framework's own `init()` calls
                // `csrf.ignoringRequestMatchers(<every endpoint it serves>)`, and no public API takes
                // an endpoint back off that list, so the one browser write on this plane is exempt.
                //
                // Turning it on here rather than off is still the safer default: it means anything
                // added to this chain that the framework does *not* own is protected without anybody
                // remembering to say so. The exemption's risk and what would end it are in the module
                // doc, §已知的不精确.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(SecurityConfig.csrfRequestHandler()))
                .securityContext(context -> context.securityContextRepository(contexts))
                // Where a browser with no session is sent. Not `formLogin`: this plane processes no
                // logins — M1 does, at /web/login — so it needs an entry point that redirects and
                // nothing else. `return_to` is the parameter LoginPage.vue already reads, and without
                // it the CLI would hang: the person signs in and lands on the home page, while the
                // listener on their loopback port waits for a callback that never comes.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new ReturnToLoginEntryPoint()));

        return http.build();
    }

    /**
     * Narrows the discovery document to what this deployment actually does.
     *
     * <p><strong>The framework's default document describes the framework, not this server.</strong>
     * Left alone it advertises every capability Spring Authorization Server has: four grant types
     * (two of which no client here may use), six ways to authenticate a client when the only client
     * is public, a JWKS endpoint, introspection, certificate-bound tokens and DPoP. Each of those is
     * a claim a client may act on, and the failure it produces is on the client's side — it asks for
     * a grant nobody can get, or fetches a document that answers 401.
     *
     * <p><strong>Each removal has a reason that lives elsewhere.</strong> {@code client_credentials}
     * is the grant ADR 0038 §决定 9 replaced with an API key, and v3 deleted the last client that
     * could use it; {@code token-exchange} was never implemented. {@code none} is the only client
     * authentication there is: the CLI is a public client and proves itself with PKCE (ADR 0026).
     * <strong>The code is still wider than this line in one place</strong> — {@code ClientRegistry}
     * registers {@code client_secret_basic} for any row that carries a secret, so a hand-seeded
     * confidential row would be served a token this document disclaims. No such row exists today
     * (rows are seeded by hand, and the CLI's has a NULL secret), so this under-promises, which is the
     * safe direction to be wrong in; if confidential clients ever become a shape here, this line and
     * that branch move together.
     * No JWKS is published because tokens are opaque and there is no public key to publish
     * (ADR 0021) — {@code /oauth2/jwks} is not even inside this chain's matcher, so it answers 401.
     *
     * <p><strong>This is a deviation, and deviations need a test that fails when they stop
     * holding.</strong> {@code AuthorizationServerIT} asserts every line below
     * ({@code theDiscoveryDocumentDescribesThisServerRatherThanTheFrameworksWholeCapability}), so a
     * framework upgrade that re-adds a default shows up as a red test rather than as a document that
     * quietly over-promises again.
     */
    private static void describeThisServer(OAuth2AuthorizationServerMetadata.Builder metadata) {
        metadata.grantTypes(grants -> only(grants, "authorization_code", "refresh_token"));
        // Revocation is on the same footing as the token endpoint: the CLI authenticates with its
        // `client_id` and nothing else (`PublicClientOnRefreshAndRevocationConverter`).
        metadata.tokenEndpointAuthenticationMethods(methods -> only(methods, "none"));
        metadata.tokenRevocationEndpointAuthenticationMethods(methods -> only(methods, "none"));
        metadata.claims(claims -> {
            claims.remove(JWKS_URI);
            claims.remove(INTROSPECTION_ENDPOINT);
            claims.remove(INTROSPECTION_ENDPOINT_AUTH_METHODS_SUPPORTED);
            claims.remove(TLS_CLIENT_CERTIFICATE_BOUND_ACCESS_TOKENS);
            claims.remove(DPOP_SIGNING_ALG_VALUES_SUPPORTED);
        });
    }

    /** Replaces a metadata list in place; the builder hands over the live list, not a copy. */
    private static void only(List<String> values, String... kept) {
        values.clear();
        values.addAll(List.of(kept));
    }

    /**
     * Sends a browser to the login page, remembering what it was trying to reach.
     *
     * <p>The framework's {@code LoginUrlAuthenticationEntryPoint} redirects to the page and stops
     * there, which is fine when the login form is the destination. Here it is not: the page belongs
     * to the SPA, and after signing in the visitor has to end up back at the authorization request —
     * otherwise the CLI's loopback listener waits for a callback that is never coming, and the only
     * symptom is a login that appeared to work.
     *
     * <p>The parameter is {@code return_to}, which is what {@code LoginPage.vue} already reads; it
     * validates the destination by origin before following it, so the open-redirect question is
     * answered on that side. What this class must get right is only that the value is the request it
     * was refusing, query string included, and that it is encoded rather than pasted.
     */
    private static final class ReturnToLoginEntryPoint implements AuthenticationEntryPoint {

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                AuthenticationException authException) throws IOException {
            String query = request.getQueryString();
            String target = request.getRequestURI() + (query == null ? "" : "?" + query);
            response.sendRedirect(
                    LOGIN_PAGE + "?return_to=" + URLEncoder.encode(target, StandardCharsets.UTF_8));
        }
    }
}
