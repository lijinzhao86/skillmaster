package com.skillmasterai.config;

import com.skillmasterai.modules.account.SessionAbsoluteLimitFilter;
import com.skillmasterai.modules.account.SessionPolicy;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.SessionRepositoryFilter;

/**
 * Hands M1's session rulers to whoever enforces them.
 *
 * <p>The policy itself is the same one-line detour {@link ApiKeyConfig} takes, for the same reason:
 * the module may not read {@link SkillmasterProperties}, so what the deployment chose arrives as a
 * bean. One line, because the property is already typed as the module's own record — the record's
 * constructor is where a pair of numbers that contradict each other is refused, so it happens while
 * the properties are being bound rather than when the first session is created (accepting #9).
 *
 * <p><strong>The ceiling is a servlet filter rather than a filter added to one security chain, and
 * that is a correctness fix rather than a preference.</strong> It was registered on the browser
 * chain only, where it worked — and the authorization server's chain, which resolves its user from
 * the same session, went on serving a login that chain had refused: eight days after signing in,
 * `/web/**` answered 401 while `/oauth/**` still had an authenticated principal, so
 * {@code skmr login} could open the consent flow and mint a fresh credential out of the login
 * this version exists to end. Measured on 2026-10-16 by aging one session row and asking each plane
 * once. Per-chain registration is what allowed that: a rule written once per chain is a rule one
 * chain does not have, and the next chain added would not have it either.
 *
 * <p><strong>The order is the whole of how it stays correct.</strong> Spring Session wraps the
 * request before anything else may look at a session
 * ({@link SessionRepositoryFilter#DEFAULT_ORDER}), and the security filter chains run well after it.
 * Sitting immediately after that one means the session has been read but no {@code SecurityContext}
 * has been loaded from it yet, so invalidating it here makes the request anonymous on *every* plane
 * — which is what "the ceiling applies to every request" has to mean (ADR 0043 §决定 1).
 */
@Configuration(proxyBeanMethods = false)
class SessionConfig {

    @Bean
    SessionPolicy sessionPolicy(SkillmasterProperties properties) {
        return properties.sessions();
    }

    /**
     * Declared as a registration and not as a bare {@code Filter} bean on purpose: Boot registers
     * every {@code Filter} bean for every request with its own order, so a bean plus this
     * registration would be the filter running twice.
     */
    @Bean
    FilterRegistrationBean<SessionAbsoluteLimitFilter> sessionAbsoluteLimit(SessionPolicy policy) {
        FilterRegistrationBean<SessionAbsoluteLimitFilter> registration =
                new FilterRegistrationBean<>(new SessionAbsoluteLimitFilter(policy));
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 1);
        return registration;
    }
}
