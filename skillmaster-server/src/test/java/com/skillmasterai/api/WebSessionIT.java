package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * What a session is worth, end to end: nothing before a login, and an identity after one.
 *
 * <p>The read endpoint here is the reason it exists. Without one authenticated endpoint on the
 * browser plane, a login could write a row and nothing would ever demonstrate that the row means
 * anything — the assertion would be about Spring Session rather than about this service.
 */
@Sql("/sql/truncate-business-tables.sql")
class WebSessionIT extends AbstractAccountIT {

    @Test
    void anAnonymousCallerHasNoSession() {
        HttpResponse<String> response = webGet(SESSION);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("unauthenticated");
    }

    /**
     * Nothing an anonymous caller can do starts a session.
     *
     * <p>This is the assertion behind the decision to turn the request cache off. With it on, the
     * framework saves the refused request — which needs a session — so every 401 wrote a row with a
     * seven-day idle timeout. It is also what leaves session fixation nothing to plant: an attacker
     * has no way to give this server a session id for a victim to adopt, because it mints one only
     * when someone signs in.
     */
    @Test
    void nothingAnonymousStartsASession() {
        assertThat(webGet(SESSION).statusCode()).isEqualTo(401);
        assertThat(webPost(LOGIN, json(Map.of("phone", randomPhone(), "password", PASSWORD)))
                .statusCode()).isEqualTo(401);

        assertThat(cookieValue(sessionCookieName)).as("no session cookie was set").isNull();
        assertThat(count("SELECT count(*) FROM spring_session")).as("no session row was written").isZero();
    }

    @Test
    void theSessionNamesTheAccountThatSignedIn() {
        String username = randomUsername();
        registerAndSignIn(username, randomPhone());

        HttpResponse<String> response = webGet(SESSION);
        JsonNode account = body(response);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(account.get("username").asText()).isEqualTo(username);
        // Sent alongside the username rather than left for the client to derive: today the personal
        // namespace's slug is the handle, and a client that knew that rule would break on the day it
        // stops being true.
        assertThat(account.get("namespace").asText()).isEqualTo(username);
        assertThat(Ulid.isValid(account.get("user_id").asText())).isTrue();
    }

    @Test
    void signingInReplacesTheSessionIdTheCallerAlreadyHad() {
        // The session-fixation defence, and nothing else performs it: the authentication filters
        // that normally rotate the id are not on this path, because the session is established in
        // the controller. A client that arrives with a session id must not keep it afterwards, or
        // anyone who can choose a victim's session id arrives signed in as them.
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        String duringRegistration = cookieValue(sessionCookieName);

        // No logout first, and that is the whole test: a client that arrives holding a *live*
        // session is the only one that can show the id was rotated. After a logout the old id is
        // gone from the store, so the next sign-in mints a new one whether or not anything rotates.
        assertThat(signIn(phone, PASSWORD).statusCode()).isEqualTo(200);
        String afterLogin = cookieValue(sessionCookieName);

        assertThat(afterLogin).isNotNull().isNotEqualTo(duringRegistration);
    }

    @Test
    void loggingInAgainIsTheSameIdentity() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        String first = body(webGet(SESSION)).get("user_id").asText();

        webPost(LOGOUT, "");
        assertThat(signIn(phone, PASSWORD).statusCode()).isEqualTo(200);

        assertThat(body(webGet(SESSION)).get("user_id").asText()).isEqualTo(first);
    }
}
