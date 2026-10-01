package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Browser;
import java.net.HttpCookie;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;

/**
 * CSRF on the browser plane, which is the part of this plane that is easy to get wrong and
 * impossible to notice: a token that is never issued and a token that is never checked both look
 * like a working server until somebody posts from another origin.
 *
 * <p>Three of these tests exist because the framework's defaults are wrong for a client that reads
 * a cookie rather than a page, and each of those defaults fails as a 403 in a place the client
 * cannot see:
 *
 * <ul>
 *   <li>the default {@code Xor} handler expects a masked token back, and the cookie holds an
 *       unmasked one;</li>
 *   <li>the default request-attribute name stops the first response from writing the cookie at
 *       all, leaving the client with nothing to send;</li>
 *   <li>and a failure raised on an endpoint that is {@code permitAll} must still be a 403, not the
 *       401 an entry point would produce — see {@code WebAuthenticationEntryPoint}.</li>
 * </ul>
 */
@Sql("/sql/truncate-business-tables.sql")
class WebCsrfIT extends AbstractAccountIT {

    @Test
    void theFirstResponseCarriesACsrfCookie() {
        // A client that has never spoken to this server before, so this is the response that has to
        // carry the token: there is no other chance to hand one over before the first write.
        Browser untouched = new Browser();
        assertThat(untouched.csrfToken()).as("a client starts with no token").isNull();

        HttpResponse<String> first = untouched.get(uri(SESSION));

        assertThat(first.statusCode()).isEqualTo(401);
        assertThat(untouched.csrfToken())
                .as("the first response set no XSRF-TOKEN cookie")
                .isNotNull();
    }

    @Test
    void aWriteWithoutTheTokenIsRefused() {
        HttpResponse<String> response = webPost(LOGIN,
                json(Map.of("phone", randomPhone(), "password", PASSWORD)), null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("forbidden");
    }

    @Test
    void aWriteWithTheWrongTokenIsRefused() {
        // Not the same test as the one above: that one proves the header is *required*, this one
        // proves its value is compared. A repository that minted a token per request — or one whose
        // cookie and header were read from different places — would leave the missing-header test
        // green while double-submit protected nothing.
        HttpResponse<String> response = webPost(LOGIN,
                json(Map.of("phone", randomPhone(), "password", PASSWORD)), "not-the-token");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("forbidden");
    }

    @Test
    void aRefusalOnAPermitAllEndpointIsA403AndNotA401() {
        // The whole reason this test exists: this endpoint is permitAll, so nothing about the
        // caller is in question — the request is refused for what it is missing. Answering 401
        // would tell the client to authenticate, and it has nothing to authenticate with.
        // A well-formed request, so that the 403 can only be about the missing token.
        HttpResponse<String> response = webPost(REGISTER_CODE, codeRequestBody(randomPhone()), null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("forbidden");
    }

    @Test
    void theCsrfCookieIsReadableAndTheSessionCookieIsNot() {
        registerAndSignIn(randomUsername(), randomPhone());

        HttpCookie csrf = cookie(Browser.CSRF_COOKIE);
        HttpCookie session = cookie(sessionCookieName);

        assertThat(csrf).as("the CSRF cookie").isNotNull();
        assertThat(session).as("the session cookie").isNotNull();
        // Asymmetric on purpose, and the asymmetry is the design: the client has to be able to read
        // the CSRF token in order to prove it can read this origin, and must not be able to read
        // the session id, which is a credential in its own right.
        assertThat(csrf.isHttpOnly()).isFalse();
        assertThat(session.isHttpOnly()).isTrue();
    }

    @Test
    void aWriteWithTheTokenSucceeds() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);

        assertThat(signIn(phone, PASSWORD).statusCode()).isEqualTo(200);
    }
}
