package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The site's one cross-origin read, end to end (ADR 0086, PRD 验收 #15).
 *
 * <p>This is the only test that runs the server with a cookie {@code Domain} and a site origin
 * configured, because those two values are exactly what production adds and what development
 * deliberately does not have. Both failures it guards are **silent**: without the {@code Domain} the
 * cookie never reaches the site, and without the CORS rule the browser refuses an answer that looks
 * fine on the wire — in each case the page simply shows the signed-out state forever, with no error
 * anywhere.
 *
 * <p>The other half of the rule is the invariant: widening the session cookie must not widen the
 * CSRF cookie. If {@code XSRF-TOKEN} ever carries a {@code Domain}, every subdomain can read it and
 * combine it with the shared session — which is a write, and ADR 0086 decision 2 says the site has
 * none.
 */
@TestPropertySource(properties = {
        "skillmaster.site.origin=https://www.skillmasterai.com",
        "skillmaster.site.cookie-domain=skillmasterai.com",
})
class SiteSessionIT extends AbstractAccountIT {

    private static final String SITE = "https://www.skillmasterai.com";
    /** Registered by this test alone, so no other test's account is disturbed. */
    private static final String PHONE = "13000000077";

    private HttpResponse<String> preflight(String path) {
        return browser.request(HttpRequest.newBuilder(uri(path))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", SITE)
                .header("Access-Control-Request-Method", "GET"));
    }

    @Test
    void theSessionCookieReachesTheSiteAndTheCsrfCookieDoesNot() throws Exception {
        registerAndSignIn("site-session", PHONE);

        HttpCookie session = cookie("SKILLMASTER_SESSION");
        assertThat(session).as("the session cookie exists after signing in").isNotNull();
        assertThat(session.getDomain())
                .as("the site can only read the session if the cookie's Domain covers it")
                .isEqualTo("skillmasterai.com");
        assertThat(session.isHttpOnly()).isTrue();

        HttpCookie csrf = cookie("XSRF-TOKEN");
        assertThat(csrf).as("the CSRF cookie is written beside the session").isNotNull();
        // A host-only cookie still reports a domain to `HttpCookie` — the request's own host — so
        // the meaningful assertion is that it does not carry the configured one.
        assertThat(csrf.getDomain())
                .as("the CSRF cookie stays host-only — widening it would hand writes to every subdomain")
                .doesNotEndWith("skillmasterai.com");
    }

    @Test
    void theSiteMayReadTheSessionButNothingElseOnTheWebPlane() throws Exception {
        HttpResponse<String> allowed = preflight("/web/session");

        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin"))
                .contains(SITE);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials"))
                .contains("true");

        HttpResponse<String> denied = preflight("/web/keys");
        assertThat(denied.headers().firstValue("Access-Control-Allow-Origin"))
                .as("only /web/session is registered — a wider rule would be a wider contract")
                .isEmpty();
    }

    /**
     * What this proves: the response carries the account and the site's origin in
     * {@code Access-Control-Allow-Origin}.
     *
     * <p><strong>What it does not prove: that a browser would send the cookie.</strong> The JDK's
     * {@code CookieManager} hands a {@code Domain=skillmasterai.com} cookie to
     * {@code http://localhost} as well (verified with a standalone probe), so this request carries the
     * session for reasons a real browser would not share. Browser cookie routing is the one link in
     * the chain only a browser can demonstrate — see the test plan's T15.
     */
    @Test
    void theSessionReadAnswersTheAccountToACrossOriginCaller() throws Exception {
        registerAndSignIn("site-read", "13000000078");

        HttpResponse<String> response = browser.request(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/web/session"))
                        .GET()
                        .header("Origin", SITE));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"username\"");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(SITE);
    }
}
