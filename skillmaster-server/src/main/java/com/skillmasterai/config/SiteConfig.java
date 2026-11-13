package com.skillmasterai.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The two server-side facts the marketing site needs, and nothing else (ADR 0086).
 *
 * <p><strong>1 · The session cookie may be read by the site.</strong> The site asks
 * {@code GET /web/session} cross-origin to learn who is looking. The cookie is {@code HttpOnly}, so
 * the browser attaches it only if the cookie's {@code Domain} covers both hosts — hence
 * {@code skillmaster.site.cookie-domain}. Left blank (development, tests) the cookie stays host-only,
 * exactly as before this change.
 *
 * <p><strong>2 · One path, a named list of origins, one method.</strong> The CORS rule is
 * deliberately the narrowest thing that works: {@code /web/session}, {@code GET}, the sites named in
 * configuration — not {@code /web/**} and not every method. The list exists because the landing page
 * is served from two names during the handover ({@code www} and {@code home}, ADR 0190); it is still
 * an explicit list, never a wildcard. Everything else on this plane is called same-origin by the
 * console, which needs no CORS at all. Note that this does <em>not</em> widen what the site can do:
 * the CSRF cookie stays host-only (see the {@link DefaultCookieSerializer} customizer below), so the
 * site still cannot write.
 */
@Configuration(proxyBeanMethods = false)
class SiteConfig {

    /** The path the site reads. Literal, because {@code api}'s route constants are not visible here. */
    private static final String SESSION_PATH = "/web/session";

    /**
     * The CORS rule for the site's one read.
     *
     * <p>An empty or blank origin registers nothing: a deployment that has not said where the site
     * lives gets no CORS at all, rather than a rule that quietly matches everything.
     *
     * <p>It accepts a comma-separated list (ADR 0190). During the handover {@code www} and
     * {@code home} serve the same page and both read the session; the list is explicit, so a
     * wildcard or an empty entry stops the boot instead of silently widening or narrowing the rule.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${skillmaster.site.origin:}") String origin) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> sites = normalizeOrigins(origin);
        if (sites.isEmpty()) {
            return source;
        }
        CorsConfiguration read = new CorsConfiguration();
        read.setAllowedOrigins(sites);
        read.setAllowedMethods(List.of("GET"));
        // `Accept` is the only header the request sets; the browser adds the rest itself.
        read.setAllowedHeaders(List.of("Accept"));
        // The session cookie has to travel, and the browser refuses a credentialed response whose
        // origin is `*` — which is the other reason the origin is a value and not a wildcard.
        read.setAllowCredentials(true);
        read.setMaxAge(Duration.ofMinutes(10));
        source.registerCorsConfiguration(SESSION_PATH, read);
        return source;
    }

    /**
     * Adds the session cookie's {@code Domain}, and only that.
     *
     * <p><strong>It customizes Boot's serializer instead of replacing it.</strong> Boot builds the
     * serializer from {@code server.servlet.session.cookie.*} — name, path, same-site, secure — and
     * offers this interface for exactly one knob it has no property of ours for. Replacing the bean
     * would work today and drift tomorrow: every future Boot property added to that block would
     * silently stop applying here.
     *
     * <p><strong>Only the session cookie gets a domain.</strong> {@code XSRF-TOKEN} is created by
     * {@code CookieCsrfTokenRepository} elsewhere and stays host-only; widening it would hand the CSRF
     * token — deliberately script-readable — to every subdomain, and with it the ability to write.
     * That is the invariant ADR 0086 decision 3 names, and it is asserted in {@code SiteSessionIT}.
     */
    @Bean
    DefaultCookieSerializerCustomizer sessionCookieDomain(
            @Value("${skillmaster.site.cookie-domain:}") String configured) {
        String domain = normalizeDomain(configured);
        return (serializer) -> {
            if (domain != null) {
                serializer.setDomainName(domain);
            }
        };
    }

    /**
     * The configured site origins, in the order written; empty when the deployment named none.
     *
     * <p>A trailing slash is stripped because a browser's {@code Origin} header never carries one —
     * leaving it would make the rule match nothing, and the page would quietly stay signed-out.
     *
     * <p>Commas separate origins (ADR 0190: {@code www} and {@code home} both serve the landing page
     * during the handover). A wildcard is refused, and so is an empty entry — {@code https://a,} is a
     * typo, and quietly dropping it would leave one name signed-out with no symptom anywhere.
     * Duplicates collapse.
     */
    static List<String> normalizeOrigins(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        List<String> origins = new ArrayList<>();
        for (String part : configured.split(",", -1)) {
            String trimmed = part.trim();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (trimmed.isEmpty()) {
                throw new IllegalStateException(
                        "skillmaster.site.origin has an empty entry, found: " + configured);
            }
            if (trimmed.contains("*") || trimmed.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalStateException(
                        "skillmaster.site.origin must name origins, found: " + configured);
            }
            if (!origins.contains(trimmed)) {
                origins.add(trimmed);
            }
        }
        return List.copyOf(origins);
    }

    /**
     * The configured cookie domain, or null when the cookie should stay host-only.
     *
     * <p>A leading dot is stripped: the legacy spelling {@code .example.com} is rejected by Tomcat's
     * cookie validation (RFC 6265 has no such form, and the browser's cookie jar treats it as
     * {@code example.com} anyway).
     *
     * <p>A domain that is not a domain is refused rather than set: a scheme, a port, a path or a
     * space produces a cookie the browser silently drops, and the symptom is the site never seeing a
     * signed-in visitor — with no error anywhere.
     */
    static String normalizeDomain(String domain) {
        if (domain == null) {
            return null;
        }
        String trimmed = domain.trim();
        while (trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.contains("/") || trimmed.contains(":") || trimmed.contains(",")
                || trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("skillmaster.site.cookie-domain must be a bare domain, "
                    + "found: " + domain);
        }
        return trimmed;
    }
}
