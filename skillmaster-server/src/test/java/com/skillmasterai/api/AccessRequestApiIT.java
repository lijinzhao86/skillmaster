package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * Asking for a rung from the API plane (v10, ADR 0052): the request is its own object, grants
 * nothing, and can be read and withdrawn by the person who made it.
 *
 * <p>Approval stays on the browser plane and is covered by {@code WebSkillMetadataIT}; this file is
 * about the verb the CLI needed, not a second way to decide.
 */
@Sql("/sql/truncate-business-tables.sql")
class AccessRequestApiIT extends AbstractAccountIT {

    private static final String SKILLS = "/api/v1/skills";

    private String owner;
    private String ownerId;
    private String friend;
    private String friendToken;

    @BeforeEach
    void twoAccounts() {
        String friendPhone = randomPhone();
        friend = randomUsername();
        friendToken = tokenFor(registerAndSignIn(friend, friendPhone));
        // The shared browser must end up as the owner: every `webPost` below is theirs.
        owner = randomUsername();
        ownerId = registerAndSignIn(owner, randomPhone());
        String draftId = submitDraft();
        publish(draftId);
    }

    @Test
    void askingRecordsTheAskAndGrantsNothing() {
        assertThat(share("browse").statusCode()).isEqualTo(201);
        HttpResponse<String> asked = ask("write");

        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(201);
        assertThat(JSON.readTree(asked.body()).get("rung").asText()).isEqualTo("write");
        assertThat(count("SELECT COUNT(*) FROM skill_access_request")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM skill_grant")).isEqualTo(1);

        JsonNode status = JSON.readTree(
                get(path() + "/access-requests/me", friendToken).body());
        assertThat(status.get("pending_rung").asText()).isEqualTo("write");

        // Nothing about what they can read moved: the grant is still the browse they had.
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools", friendToken).statusCode())
                .isEqualTo(200);
    }

    @Test
    void askingAgainMovesTheOneRowRatherThanAddingASecond() {
        assertThat(share("browse").statusCode()).isEqualTo(201);
        assertThat(ask("write").statusCode()).isEqualTo(201);
        assertThat(ask("maintain").statusCode()).isEqualTo(201);

        assertThat(count("SELECT COUNT(*) FROM skill_access_request")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM skill_access_request"
                + " WHERE wanted_role = 'maintain'")).isEqualTo(1);
    }

    @Test
    void theAskMustBeAboveWhatYouHoldAndNotFromAnOwner() {
        assertThat(share("browse").statusCode()).isEqualTo(201);

        HttpResponse<String> below = ask("browse");
        assertThat(below.statusCode()).isEqualTo(400);
        assertThat(issueOf(below)).isEqualTo("not_above_current_rung");

        HttpResponse<String> nonsense = ask("editor");
        assertThat(nonsense.statusCode()).isEqualTo(400);
        assertThat(issueOf(nonsense)).isEqualTo("not_a_rung");

        HttpResponse<String> byOwner = send(request(path() + "/access-requests", ownerToken())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("rung", "write"))))
                .build());
        assertThat(byOwner.statusCode()).isEqualTo(400);
        assertThat(issueOf(byOwner)).isEqualTo("owner_has_no_higher_rung");
    }

    @Test
    void aStrangerCannotSeeTheSkillLetAloneAsk() {
        String stranger = randomUsername();
        String strangerToken = tokenFor(registerAndSignIn(stranger, randomPhone()));

        assertThat(send(request(path() + "/access-requests", strangerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("rung", "write"))))
                .build()).statusCode()).isEqualTo(404);
    }

    @Test
    void withdrawingIsIdempotentAndStatusSaysSo() {
        assertThat(share("browse").statusCode()).isEqualTo(201);
        assertThat(ask("write").statusCode()).isEqualTo(201);

        assertThat(send(request(path() + "/access-requests/me", friendToken).DELETE().build())
                .statusCode()).isEqualTo(204);
        assertThat(send(request(path() + "/access-requests/me", friendToken).DELETE().build())
                .statusCode()).isEqualTo(204);

        JsonNode status = JSON.readTree(
                get(path() + "/access-requests/me", friendToken).body());
        assertThat(status.get("pending_rung").isNull()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM skill_access_request")).isZero();
    }

    @Test
    void anAskChangesNothingTheCallerCanRead() {
        assertThat(share("browse").statusCode()).isEqualTo(201);
        String metadataBefore = get(path() + "/metadata", friendToken).body();
        String versionsBefore = get(path() + "/versions", friendToken).body();

        assertThat(ask("write").statusCode()).isEqualTo(201);

        assertThat(get(path() + "/metadata", friendToken).body()).isEqualTo(metadataBefore);
        assertThat(get(path() + "/versions", friendToken).body()).isEqualTo(versionsBefore);
    }

    // ------------------------------------------------------------------ helpers

    private String path() {
        return SKILLS + "/" + owner + "/pdf-tools";
    }

    private String ownerToken() {
        return tokenFor(ownerId);
    }

    private HttpResponse<String> ask(String rung) {
        return send(request(path() + "/access-requests", friendToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("rung", rung))))
                .build());
    }

    private HttpResponse<String> share(String role) {
        return send(request(path() + "/grants", ownerToken())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("handle", friend, "role", role))))
                .build());
    }

    private String issueOf(HttpResponse<String> response) {
        return JSON.readTree(response.body()).get("error").get("details").get(0)
                .get("issue").asText();
    }

    private String submitDraft() {
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(Map.of("SKILL.md",
                        "---\nname: pdf-tools\ndescription: d\n---\n")))
                .field("name", "第一版");
        HttpResponse<String> response = send(request(SKILLS, ownerToken())
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
        assertThat(response.statusCode()).as("submit failed: %s", response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("version").get("draft_id").asText();
    }

    private void publish(String draftId) {
        HttpResponse<String> response = webPost(
                "/web/skills/" + owner + "/pdf-tools/publish",
                json(Map.of("version", draftId, "release_name", "1.0.0")));
        assertThat(response.statusCode()).as("publish failed: %s", response.body()).isEqualTo(200);
    }
}
