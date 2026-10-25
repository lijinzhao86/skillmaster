package com.skillmasterai.config;

import com.skillmasterai.modules.auth.BearerAuthenticationEntryPoint;
import com.skillmasterai.modules.auth.BearerTokenSecurityContextRepository;
import com.skillmasterai.modules.auth.InsufficientScopeAccessDeniedHandler;
import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.auth.TokenValidator;
import com.skillmasterai.modules.auth.WebAccessDeniedHandler;
import com.skillmasterai.modules.auth.WebAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the two credential planes of §4.1 into the servlet chain — who a caller is, and what a
 * request needs.
 *
 * <p><strong>One chain per plane, each matching its own path prefix.</strong> A bearer token
 * authenticates {@code /api/v1/**} and nothing else; a session cookie authenticates {@code /web/**}
 * and nothing else. The point is not tidiness: without the split, a single chain would have to
 * accept either credential on either path, and "a valid token can read the browser plane" is the
 * kind of hole that looks like a feature until it is not.
 *
 * <p><strong>Both chains carry an explicit {@link Order}.</strong> Spring is free to pick any order
 * for two unannotated chains, and the failure is silent in the direction that matters: the bearer
 * chain's {@code anyRequest().authenticated()} would answer every login with a 401 carrying a
 * bearer challenge. The narrower matcher is ordered first so the API chain keeps its plain
 * {@code anyRequest()} and needs no negation.
 *
 * <p>Default-deny still holds. Every endpoint is private unless a rule below says otherwise, so
 * forgetting to protect a new path is a 401 rather than an open door.
 *
 * <p>What this class is <em>not</em> allowed to decide is whether a caller may see a particular
 * skill. That is visibility, it belongs to M4, and it renders as 404 (see
 * {@code InsufficientScopeAccessDeniedHandler}).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    /**
     * The browser plane: a session cookie, and the CSRF token that has to come back with every
     * write.
     *
     * <p><strong>CSRF is on and is configured by hand.</strong> The other chain disables it because
     * a bearer token is not ambient authority; here the credential <em>is</em> a cookie the browser
     * attaches on its own, which is exactly the situation the protection exists for. What makes the
     * configuration non-default is the pair of settings in {@link #csrfRequestHandler()}.
     *
     * <p>The six write endpoints are {@code permitAll} and the read is not: they are how an
     * anonymous caller becomes a known one. Their protection is CSRF, not authorization — which is
     * only sound because the CSRF filter refuses an unaccompanied write before routing reaches the
     * controller.
     *
     * <p><strong>{@code /web/session} exists so that {@code authenticated()} has something to
     * guard.</strong> Without one endpoint on the plane that reads the session back, no request
     * would ever demonstrate that a login produced a session — the tests could assert only that
     * Spring Session wrote a row, which is an assertion about the framework.
     */
    @Bean
    @Order(1)
    SecurityFilterChain webSecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
            SecurityContextRepository contexts, CsrfTokenRepository csrf) throws Exception {
        http
                .securityMatcher("/web/**")
                // The site's one cross-origin read (ADR 0086). The rule itself is in SiteConfig, and
                // a chain without this line would answer that read with no CORS headers at all —
                // which the browser reports as a failed request and looks exactly like an outage.
                .cors(Customizer.withDefaults())
                .csrf(csrfConfigurer -> csrfConfigurer
                        .csrfTokenRepository(csrf)
                        .csrfTokenRequestHandler(csrfRequestHandler()))
                .httpBasic(AbstractHttpConfigurer::disable)
                // No form and no redirect to one: this plane answers JSON, and a login page is M2's
                // to serve — it is what §4.4's authorization endpoint needs when a browser arrives
                // unauthenticated. Until then, the entry point below is the whole of the answer.
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // Sessions are the point here, unlike the other chain. `IF_REQUIRED` rather than
                // `ALWAYS`: a request that never asks for one must not create a row.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context.securityContextRepository(contexts))
                // The framework's default cache stores the request that was refused so that a
                // redirect-to-login can return to it. Nothing on this plane redirects anywhere, and
                // the cache is not passive: saving a request calls for a session, so every
                // anonymous 401 was writing a row — with a seven-day idle timeout, on an endpoint
                // anyone can call in a loop.
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/web/session").authenticated()
                        // The author's own skills, including drafts, and the two writes that change
                        // what is live (ADR 0031). `authenticated()` rather than the `/web/**`
                        // permitAll below it, because unlike the login and reset endpoints these
                        // read and write one particular person's data — there is no anonymous answer
                        // to give, and an empty page would be a worse one than a 401.
                        .requestMatchers("/web/skills/**").authenticated()
                        // The publish queue (v18). Every answer on it is about one person's own
                        // drafts, and it must not fall to the permitAll below — the same reason the
                        // author's skills line above exists. Named by path because `config` may not
                        // depend on `api`, so it cannot borrow the constant.
                        .requestMatchers("/web/publish-queue").authenticated()
                        // v14's library, discovery and tag screens. Every one of them answers
                        // about one person's data or one person's library, so there is no anonymous
                        // answer to give; without these lines they would fall to the permitAll
                        // below. `/web/library` and `/web/tags` are named exactly as well as by
                        // prefix, because a matcher ending in `/**` matches sub-paths and the
                        // product has both a page and its members (TD §架构).
                        .requestMatchers("/web/library", "/web/discover", "/web/discover/**",
                                "/web/tags", "/web/tags/**", "/web/categories",
                                "/web/public-tags").authenticated()
                        // The credential screen (ADR 0038). Authenticated for the same reason the
                        // author's own skills are: every answer on it is about one person's data, and
                        // there is no anonymous answer to give. Without this line it would fall to the
                        // permitAll below and **issuing a key would be open to the world** — the CSRF
                        // filter would still refuse a cross-site write, but nothing would ask who is
                        // asking. Named by area rather than by a constant from `api`, for the reason
                        // this class's first paragraph gives: `config` may not depend on `api`.
                        .requestMatchers("/web/keys/**").authenticated()
                        // The login devices (ADR 0043). Authenticated for the third time for the
                        // same one reason: every answer on it is about one person's own sessions, and
                        // there is no anonymous answer to give. Allowing it to fall through would
                        // expose the list of addresses and clients an account has signed in from,
                        // which is a description of where somebody works.
                        .requestMatchers("/web/sessions/**").authenticated()
                        // The CLI's login (v4). Authenticated for the fourth time for the same one
                        // reason, and here the line is load-bearing in the strongest sense: without
                        // it these two writes fall to the permitAll below and **anybody could mint a
                        // login credential**. The CSRF filter would still refuse a cross-site write,
                        // but nothing would ask who is asking — the identical defect the
                        // `/web/keys/**` line above was written about.
                        .requestMatchers("/web/cli-sign-ins/**").authenticated()
                        .requestMatchers("/web/**").permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new WebAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new WebAccessDeniedHandler(objectMapper)));

        return http.build();
    }

    /**
     * The API plane: a bearer token per request, and no session at all.
     *
     * <p>Stateless is not an optimisation — it is what makes the two planes separable. There is no
     * ambient authority for a cross-site request to borrow, which is why CSRF is off here and on
     * there.
     */
    @Bean
    @Order(2)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, TokenValidator tokenValidator,
            SkillmasterProperties properties, ObjectMapper objectMapper) throws Exception {
        String baseUrl = properties.publicBaseUrl();

        http
                // No cookies and no browser session: every request carries its own token, so
                // there is no ambient authority for a cross-site request to borrow.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Health is unauthenticated so a load balancer can probe it.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // **`/error` must not require a token, or the error never gets reported.**
                        // When a request whose chain is *above* this one fails, the container
                        // dispatches to `/error` — and that dispatch re-matches the chains from the
                        // top, landing here, because `/error` matches neither the OAuth plane nor
                        // `/web`. Denying it means the API plane's entry point answers instead of
                        // the error page, so the real status is replaced by a 401 with a Bearer
                        // challenge. Observed 2026-10-05: a bad consent submission answered
                        // `401 unauthenticated` where the framework had produced a 400 explaining
                        // what was wrong with it. Nothing is exposed by allowing it: the body is
                        // Spring's error document, which by default carries no message and no
                        // stack trace.
                        .requestMatchers("/error").permitAll()
                        // **The one public write this server has** (v4): a CLI redeeming a sign-in
                        // code, at an address matching neither the OAuth plane nor `/web`. `permitAll`
                        // because the alternative is worse than it looks — the code, the PKCE verifier
                        // and a per-address limit are the whole of its authentication, and requiring a
                        // token here would make it unreachable by exactly the caller it exists for. A
                        // fourth filter chain was considered and rejected: it reads better, but it
                        // forces the two orders above to be renumbered, and getting that wrong makes
                        // Spring refuse to start (UnreachableFilterChainException).
                        //
                        // Two consequences of landing in this chain, both accepted: CSRF is off here,
                        // which is correct — the request carries no ambient authority to forge — and a
                        // request that also sends a bogus Bearer header is answered 401 by the
                        // validator rather than `code_not_found`. Harmless, and arguably truer.
                        .requestMatchers("/cli-sign-ins/exchange").permitAll()
                        // The discovery channel (§1.5) and the gateway skill's own routes (§4.5).
                        // Anonymous by design, not by omission: this is how a machine that has
                        // never authenticated learns where to authenticate, so requiring a token
                        // would make it unreachable by exactly the clients it exists for. §1.5
                        // checked the convention for both authentication and gating and found
                        // neither, which is why publishing one public artefact through it is safe:
                        // there is nothing here but the gateway, and the gateway only says where
                        // the API is.
                        .requestMatchers("/.well-known/**", "/gateway/**").permitAll()
                        // Read for GET, write for everything else. Scopes.requiredForMethod is
                        // the same function the 403 challenge uses to name the missing scope, so
                        // the challenge cannot advertise a scope that is not actually enforced —
                        // and the contract test fails if the two ever drift apart.
                        //
                        // HEAD and OPTIONS are listed with GET because that function maps them to
                        // the read scope too, and Spring serves HEAD through the GET handler. Left
                        // out, they fell to the write rule below and were denied with a challenge
                        // naming the read scope the caller already had. One line per method: the
                        // registry takes several paths for one method, not several methods.
                        // The `/api` segment is §4.1's plane prefix. The same path shape under
                        // `/web` belongs to the browser and carries a session cookie instead of a
                        // token — that plane's chain is above, and it is what keeps the two from
                        // being confused. Under `/inner` it belongs to the operator and will not be
                        // exposed at all.
                        // Before the scope rules below, and authenticated rather than authorized:
                        // "who am I and what am I holding" must not require a permission. The moment
                        // this endpoint matters most is the moment something is wrong with the
                        // credential, and a key that somehow lacks skills:read would then be unable to
                        // report what it is — which is exactly what `login --with-key` and
                        // `auth status` need it for.
                        .requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAuthority(readAuthority())
                        .requestMatchers(HttpMethod.HEAD, "/api/v1/**").hasAuthority(readAuthority())
                        .requestMatchers(HttpMethod.OPTIONS, "/api/v1/**").hasAuthority(readAuthority())
                        .requestMatchers("/api/v1/**").hasAuthority(writeAuthority())
                        .anyRequest().authenticated())
                // The context comes from the token, via the repository — see that class for why
                // this is not a custom filter that authenticates on its own.
                .securityContext(context -> context
                        .securityContextRepository(
                                new BearerTokenSecurityContextRepository(tokenValidator)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(
                                new BearerAuthenticationEntryPoint(baseUrl, objectMapper))
                        .accessDeniedHandler(
                                new InsufficientScopeAccessDeniedHandler(baseUrl, objectMapper)));

        return http.build();
    }

    /**
     * Where the browser plane's security context lives between requests.
     *
     * <p>Declared as a bean because two things need the same instance: the chain, which reads it,
     * and {@code WebSession}, which saves to it at login. Two repositories would be a login that
     * answers 200 and leaves the next request anonymous.
     *
     * <p>The delegating pair is the framework's own arrangement: within one request the context is
     * also held as a request attribute, so a forward or an error dispatch sees what the controller
     * just set, and the session copy is what survives to the next request — which under Spring
     * Session means a row, not a heap.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    /**
     * The CSRF token as a readable cookie, so that a client can send it back in a header.
     *
     * <p>Readable by script on purpose, and that is the whole of the double-submit design: the
     * token is not a secret the page must keep from itself, it is proof that the request was
     * composed by something that could read this origin. The session cookie beside it stays
     * HttpOnly (see {@code application.yml}) — the asymmetry is deliberate.
     */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    /**
     * The plain token handler, named to null — both parts of that are load-bearing, and both
     * failures look like a client that cannot get past its first POST.
     *
     * <p><strong>Not the {@code Xor} handler the framework defaults to.</strong> It masks the token
     * it exposes so that a page's own HTML cannot leak it, and expects the masked value back. Our
     * client never sees a page; it reads the raw value out of the cookie and echoes it, which the
     * masked handler rejects on every request.
     *
     * <p><strong>And the request attribute name is set to null rather than left at its default of
     * {@code "_csrf"}.</strong> With a name set, the handler stores a lazy token under it and
     * returns without ever resolving it, so nothing asks the repository for a token and the first
     * response carries no {@code XSRF-TOKEN} cookie at all — leaving the client with nothing to
     * send. Null makes the handler ask the token for its parameter name, and answering that
     * resolves it, which is what writes the cookie.
     */
    /**
     * Package-private rather than private: {@link TokenSecurityConfig} needs the same handler for the
     * consent submission, and two handlers would be two behaviours for one token — the consent POST
     * would be refused while every other write worked, which reads like a broken form.
     */
    static CsrfTokenRequestHandler csrfRequestHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    private static String readAuthority() {
        return Scopes.authority(Scopes.requiredForMethod(HttpMethod.GET.name()));
    }

    private static String writeAuthority() {
        return Scopes.authority(Scopes.requiredForMethod(HttpMethod.POST.name()));
    }
}
