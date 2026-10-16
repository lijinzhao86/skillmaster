package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;

/**
 * §4.1's two credential planes are actually separate.
 *
 * <p>This is the test that catches a chain-ordering mistake, which is a mistake nothing else
 * catches: with the bearer chain first, every one of the browser plane's endpoints answers 401 with
 * a bearer challenge, and the six endpoints that are supposed to be reachable by an anonymous
 * caller are not. Both chains carry an explicit {@code @Order} for that reason, and this is what
 * holds them to it.
 *
 * <p>The assertions are deliberately about which chain served the request rather than only about the
 * status: a 401 could come from either. The presence of the bearer challenge is what distinguishes
 * them, because only one plane sends it.
 */
@Sql("/sql/truncate-business-tables.sql")
class WebSecurityPlaneIT extends AbstractAccountIT {

    @Test
    void aBearerTokenDoesNotAuthenticateTheBrowserPlane() {
        HttpResponse<String> response = get(SESSION, token());

        assertThat(response.statusCode()).isEqualTo(401);
        // No challenge on this plane even when the caller is a machine: a client holding a session
        // cookie that is told to go and find a bearer token has been sent the wrong way.
        assertThat(wwwAuthenticate(response)).isNull();
    }

    @Test
    void aSessionCookieDoesNotAuthenticateTheApiPlane() {
        registerAndSignIn(randomUsername(), randomPhone());
        assertThat(webGet(SESSION).statusCode()).isEqualTo(200);

        HttpResponse<String> response = webGet("/api/v1/skills");

        assertThat(response.statusCode()).isEqualTo(401);
        // Which plane answered, proven rather than assumed: this header is the API chain's.
        assertThat(wwwAuthenticate(response)).isNotNull();
    }

    @Test
    void anAnonymousBrowserPlaneRequestIsAnsweredWithoutABearerChallenge() {
        HttpResponse<String> response = webGet(SESSION);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(response)).isNull();
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("unauthenticated");
    }

    /**
     * The author's own skills are the newest thing on this plane, and the one a token must not buy
     * (ADR 0031).
     *
     * <p>{@code /web/session} is guarded for a different reason — it is how a login is proven — and a
     * chain-ordering mistake that let a token through on it would be caught above. This is the case
     * where letting one through would hand an agent every draft its owner has written, which is
     * precisely the material publishing exists to keep back.
     */
    @Test
    void aBearerTokenDoesNotAuthenticateTheAuthorsSkills() {
        HttpResponse<String> response = get("/web/skills", token());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(response))
                .as("no bearer challenge on this plane, however the caller is dressed")
                .isNull();
    }

    @Test
    void anAnonymousRequestForTheAuthorsSkillsIs401() {
        HttpResponse<String> response = webGet("/web/skills");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(response)).isNull();
        assertThat(body(response).get("error").get("code").asText())
                .as("an empty page would be the worse answer: there is no anonymous version of "
                        + "'your skills'")
                .isEqualTo("unauthenticated");
    }

    /**
     * And the same for the login devices, which is a route that answers <em>differently</em> when the
     * guard is missing — 500 rather than an empty list.
     *
     * <p><strong>This case exists because nothing else could fail.</strong> Every request in
     * {@code WebSessionsIT} is made by a signed-in browser, so deleting the matcher for
     * {@code /web/sessions/**} left that whole class green: an anonymous caller fell through to the
     * controller, which needs the current session id to mark 「当前设备」 and threw. A 401 is the
     * answer; a 500 is a bug report about a bug report.
     */
    @Test
    void anAnonymousRequestForTheLoginDevicesIs401() {
        HttpResponse<String> response = webGet("/web/sessions");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(response)).isNull();
        assertThat(body(response).get("error").get("code").asText())
                .as("there is no anonymous version of 'your devices' either")
                .isEqualTo("unauthenticated");
    }
}
