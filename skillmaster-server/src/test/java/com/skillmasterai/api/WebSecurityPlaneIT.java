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
}
