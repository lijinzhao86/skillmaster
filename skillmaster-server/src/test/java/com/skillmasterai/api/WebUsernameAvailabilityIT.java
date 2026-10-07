package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The username check a registration form calls as soon as the field is left.
 *
 * <p>What it has to get right is that "free" means free. Registration's guard is two UNIQUE
 * constraints over two tables, and a check that asked only the obvious one would promise a name that
 * the submit then refuses — after a text message has been sent to pay for the discovery.
 *
 * <p>That this is not an account-existence oracle is a property of what a username is, not of
 * anything here: see {@code CheckUsernameUseCase}.
 */
@Sql("/sql/truncate-business-tables.sql")
class WebUsernameAvailabilityIT extends AbstractAccountIT {

    private static final String AVAILABILITY = "/web/username/availability";

    @Test
    void aNameNobodyHasIsFree() {
        JsonNode answer = ask(randomUsername());

        assertThat(answer.get("available").asBoolean()).isTrue();
        assertThat(answer.get("issue").isNull()).as("a free name has nothing to say about it").isTrue();
    }

    @Test
    void aNameSomebodyRegisteredIsNot() {
        String username = randomUsername();
        registerAndSignIn(username, randomPhone());

        JsonNode answer = ask(username);

        assertThat(answer.get("available").asBoolean()).isFalse();
        assertThat(answer.get("issue").asText()).isEqualTo("already_taken");
    }

    @Test
    void theReservedNameIsNotFree() {
        // The reserved name, which is *not* the case that needs the second table: V2 seeds an account
        // whose handle is `skillmaster` on purpose, so the name is held by UNIQUE(app_user.handle)
        // rather than by a deny list in code. Asking accounts alone would still catch this one.
        JsonNode answer = ask("skillmaster");

        assertThat(answer.get("available").asBoolean()).isFalse();
        assertThat(answer.get("issue").asText()).isEqualTo("already_taken");
    }

    @Test
    void aNamespaceWithNoAccountBehindItIsNotFree() {
        // The case that makes asking one table wrong — the reason this second read exists at all. A
        // namespace slug does not have to be anybody's handle: nothing in v1 creates one, but the
        // schema permits it and an organisation namespace would, and the registration is refused by
        // UNIQUE(namespace.slug) whether or not an account holds the name.
        //
        // Written as a row rather than reached through an endpoint, because no endpoint can create
        // this state yet — which is exactly why the guard has to be pinned here or not at all.
        String slug = randomUsername();
        jdbc.sql("INSERT INTO namespace (id, slug, title, owner_user_id, visibility, created_at)"
                        + " VALUES (:id, :slug, 'An organisation', :owner, 'private',"
                        + " '2026-10-02T00:00:00Z')")
                // The seeded demo account: the owner is not what this test is about, only a row the
                // foreign key will accept.
                .param("owner", "01M3HTG7GCCVBGRPAFFSVSF12W")
                .param("id", Ulid.generate())
                .param("slug", slug)
                .update();

        JsonNode answer = ask(slug);

        assertThat(answer.get("available").asBoolean())
                .as("a namespace slug with no matching handle").isFalse();
        assertThat(answer.get("issue").asText()).isEqualTo("already_taken");
    }

    @Test
    void aNameThatCouldNotBeOneIsAnsweredForItsShape() {
        // The same wording the submit would refuse it with, because it is the same policy object.
        // Long enough to clear the floor on purpose: the alphabet is judged first, so a case that
        // failed both would be answered for the length and pass this for the wrong reason.
        assertThat(ask("Alice!").get("issue").asText()).as("Upper case and punctuation")
                .isEqualTo("invalid_format");
        assertThat(ask("-abcdef").get("issue").asText()).as("A leading hyphen")
                .isEqualTo("invalid_format");
        assertThat(ask("abcde").get("issue").asText()).as("Too short")
                .isEqualTo("invalid_length");
        assertThat(ask("a".repeat(31)).get("issue").asText()).as("Too long")
                .isEqualTo("invalid_length");
    }

    @Test
    void askingWithNoNameIsAnsweredInTheSameEnvelope() {
        // Rather than the framework's own missing-parameter 400: every answer this plane gives has to
        // carry the envelope, or a client reads a bare error page as an internal failure of ours.
        JsonNode answer = ask("");

        assertThat(answer.get("available").asBoolean()).isFalse();
        assertThat(answer.get("issue").asText()).isEqualTo("required");
    }

    @Test
    void lookingUpNamesIsRateLimited() {
        assertThat(ask(randomUsername()).get("available").asBoolean()).isTrue();
        // Moved to the cap rather than spent sixty times, the way the other address rules are tested.
        jdbc.sql("UPDATE auth_throttle SET attempts = 60 WHERE scope = 'username:ip'").update();

        HttpResponse<String> refused = webGet(AVAILABILITY + "?username=" + randomUsername());

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("too_many_requests");
        assertThat(refused.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void theShapeIsAnsweredWithoutSpendingALookup() {
        // A regular expression costs nothing, so a request that never reached a table must not be
        // counted against the caller — otherwise somebody fixing a typo pays for the typo.
        ask("Alice!");

        assertThat(count("SELECT count(*) FROM auth_throttle WHERE scope = 'username:ip'"))
                .as("no lookup was made").isZero();
    }

    private JsonNode ask(String username) {
        String query = username == null || username.isEmpty() ? "" : "?username=" + username;
        HttpResponse<String> response = webGet(AVAILABILITY + query);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return body(response);
    }
}
