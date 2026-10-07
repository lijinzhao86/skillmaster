package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;

/**
 * Logout, which is the one endpoint on this plane where CSRF protection is the point rather than a
 * chore: without it, any page on the web could sign a visitor out of this one.
 *
 * <p>The session row is asserted on as well as the response, because "signed out" is a claim about
 * the server's state and not about what came back. A 204 that left the row in place would look
 * identical from outside until the next request, which would still be authenticated.
 */
@Sql("/sql/truncate-business-tables.sql")
class WebLogoutIT extends AbstractAccountIT {

    @Test
    void logoutEndsTheSession() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        String userId = body(webGet(SESSION)).get("user_id").asText();

        HttpResponse<String> response = webPost(LOGOUT, "");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = :id",
                Map.of("id", userId))).isZero();
        assertThat(webGet(SESSION).statusCode()).isEqualTo(401);
    }

    @Test
    void loggingOutTwiceIsNotAnError() {
        registerAndSignIn(randomUsername(), randomPhone());

        assertThat(webPost(LOGOUT, "").statusCode()).isEqualTo(204);
        // A client whose session expired while it was idle gets the same answer as one that was
        // signed in. The second request is still a write, so it still has to carry the token — which
        // is why the first logout leaves the CSRF cookie alone.
        assertThat(webPost(LOGOUT, "").statusCode()).isEqualTo(204);
    }

    @Test
    void logoutWithoutTheTokenIsRefusedAndTheSessionSurvives() {
        registerAndSignIn(randomUsername(), randomPhone());

        HttpResponse<String> response = webPost(LOGOUT, "", null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("forbidden");
        // Refused means refused: a third-party page must not be able to sign this person out, and
        // the session it tried to end has to still be there.
        assertThat(webGet(SESSION).statusCode()).isEqualTo(200);
    }

    @Test
    void loggingOutOfASessionThatIsAlreadyGoneIsNotAnError() {
        registerAndSignIn(randomUsername(), randomPhone());
        jdbc.sql("DELETE FROM spring_session").update();

        assertThat(webPost(LOGOUT, "").statusCode()).isEqualTo(204);
    }
}
