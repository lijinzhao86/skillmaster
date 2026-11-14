package com.skillmasterai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The two shapes of the CORS rule, without a server.
 *
 * <p>The integration test ({@code SiteSessionIT}) proves the rule reaches the wire; this one pins the
 * case that has no wire to reach — a deployment that never said where the site lives must get **no**
 * rule, not a permissive one.
 */
class SiteConfigTest {

    private static final String SITE = "https://www.skillmasterai.com";
    private static final String HOME = "https://home.skillmasterai.com";

    private static CorsConfigurationSource source(String origin) {
        return new SiteConfig().corsConfigurationSource(origin);
    }

    @Test
    void allowsTheSitesOwnOriginToReadTheSessionWithCredentials() {
        UrlBasedCorsConfigurationSource source = (UrlBasedCorsConfigurationSource) source(SITE);
        CorsConfiguration read = source.getCorsConfiguration(
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/web/session"));

        assertThat(read).isNotNull();
        assertThat(read.getAllowedOrigins()).containsExactly(SITE);
        assertThat(read.getAllowedMethods()).containsExactly("GET");
        assertThat(read.getAllowCredentials()).isTrue();
    }

    @Test
    void registersNothingElseOnTheWebPlane() {
        UrlBasedCorsConfigurationSource source = (UrlBasedCorsConfigurationSource) source(SITE);

        assertThat(source.getCorsConfiguration(
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/web/logout")))
                .isNull();
        assertThat(source.getCorsConfiguration(
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/web/keys")))
                .isNull();
    }

    @Test
    void blankOriginMeansNoRuleAtAll() {
        UrlBasedCorsConfigurationSource source = (UrlBasedCorsConfigurationSource) source("");

        assertThat(source.getCorsConfiguration(
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/web/session")))
                .isNull();
    }

    /**
     * A browser's {@code Origin} never ends in a slash, so a configured one that does would match
     * nothing — and the failure is invisible: the page simply stays signed-out forever.
     */
    @Test
    void anOriginIsComparedTheWayTheBrowserSendsIt() {
        CorsConfiguration read = ((UrlBasedCorsConfigurationSource) source("  " + SITE + "/  "))
                .getCorsConfiguration(
                        new org.springframework.mock.web.MockHttpServletRequest("GET", "/web/session"));

        assertThat(read.getAllowedOrigins()).containsExactly(SITE);
    }

    /**
     * Two names serve the landing page during the handover (ADR 0190). The list is explicit: a
     * wildcard or an empty entry is a typo with no visible symptom, so it stops the boot instead.
     */
    @Test
    void aListOfOriginsIsAllowedButAWildcardAndEmptyEntriesAreRefused() {
        CorsConfiguration read = ((UrlBasedCorsConfigurationSource) source(" " + SITE + " , " + HOME + "/ "))
                .getCorsConfiguration(
                        new org.springframework.mock.web.MockHttpServletRequest("GET", "/web/session"));

        assertThat(read.getAllowedOrigins()).containsExactly(SITE, HOME);

        CorsConfiguration deduped = ((UrlBasedCorsConfigurationSource) source(SITE + "," + SITE))
                .getCorsConfiguration(
                        new org.springframework.mock.web.MockHttpServletRequest("GET", "/web/session"));
        assertThat(deduped.getAllowedOrigins()).containsExactly(SITE);

        assertThatThrownBy(() -> source("*"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skillmaster.site.origin");
        assertThatThrownBy(() -> source(SITE + ","))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skillmaster.site.origin");
        assertThatThrownBy(() -> source("," + SITE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skillmaster.site.origin");
        // Whitespace inside an entry is a typo too — trimming only removes the ends.
        assertThatThrownBy(() -> source(SITE + " https://example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skillmaster.site.origin");
    }

    /**
     * The cookie's domain is normalized to the bare form RFC 6265 defines. `.example.com` is the
     * legacy spelling Tomcat refuses; a scheme or port makes a cookie the browser silently drops.
     */
    @Test
    void aCookieDomainIsTheBareDomain() {
        assertThat(SiteConfig.normalizeDomain(" .skillmasterai.com ")).isEqualTo("skillmasterai.com");
        assertThat(SiteConfig.normalizeDomain("")).isNull();
        assertThat(SiteConfig.normalizeDomain("..")).isNull();
        assertThatThrownBy(() -> SiteConfig.normalizeDomain("https://skillmasterai.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skillmaster.site.cookie-domain");
        assertThatThrownBy(() -> SiteConfig.normalizeDomain("skillmasterai.com:443"))
                .isInstanceOf(IllegalStateException.class);
    }
}
