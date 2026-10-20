package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The author plane on {@code /api/v1} (ADR 0061): the CLI's version commands, end to end.
 *
 * <p>Its reads are the author's own — drafts, history and the row's derived status — and its writes
 * are the three that change nothing a caller sees: rename, note, discard. Publishing, rolling back,
 * revoking, restoring and the skill switch deliberately have no route here, and this asserts that
 * absence rather than trusting the mapping not to grow one.
 */
@Sql("/sql/truncate-business-tables.sql")
class AuthorSkillIT extends AbstractAccountIT {

    private String username;
    private String token;

    @BeforeEach
    void signUp() {
        username = randomUsername();
        token = tokenFor(registerAndSignIn(username, randomPhone()));
    }

    @Test
    void theVersionListCarriesTheDraftAndTheDerivedStatus() {
        String draftId = submitDraft("compress 补文档", "把 compress 的用法补进正文");

        JsonNode body = JSON.readTree(get(path("/versions"), token).body());

        assertThat(body.get("skill").get("address").asText()).isEqualTo(username + "/pdf-tools");
        assertThat(body.get("versions")).hasSize(1);
        JsonNode row = body.get("versions").get(0);
        assertThat(row.get("status").asText()).isEqualTo("draft");
        assertThat(row.get("name").asText()).isEqualTo("compress 补文档");
        assertThat(row.get("note").asText()).isEqualTo("把 compress 的用法补进正文");
        assertThat(row.get("version").isNull()).as("a draft has no number").isTrue();
        assertThat(row.get("pin").asText()).isEqualTo("draft:" + draftId);
        assertThat(row.get("submitted_by").asText()).isEqualTo(username);
        assertThat(body.get("counts").get("all").asInt()).isEqualTo(1);
        assertThat(body.get("next").isNull()).isTrue();
    }

    @Test
    void aDraftCanBeRenamedAndGivenANoteThenBothFreezeAtPublish() {
        String draftId = submitDraft("原名", null);

        HttpResponse<String> renamed = send(request(path("/versions/draft:" + draftId + "/name"),
                token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        json(Map.of("name", "新名字"))))
                .build());
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
        assertThat(JSON.readTree(renamed.body()).get("version").get("name").asText())
                .isEqualTo("新名字");

        HttpResponse<String> noted = send(request(path("/versions/draft:" + draftId + "/note"),
                token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        json(Map.of("note", "补了按页与按分隔符"))))
                .build());
        assertThat(noted.statusCode()).as(noted.body()).isEqualTo(200);

        publish(draftId, "1.0.0");

        HttpResponse<String> frozenName = send(request(
                path("/versions/1.0.0/name"), token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        json(Map.of("name", "late"))))
                .build());
        assertThat(frozenName.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(frozenName.body()).get("error").get("code").asText())
                .isEqualTo("draft_name_is_frozen");

        HttpResponse<String> frozenNote = send(request(path("/versions/1.0.0/note"), token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        json(Map.of("note", "late"))))
                .build());
        assertThat(frozenNote.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(frozenNote.body()).get("error").get("code").asText())
                .isEqualTo("note_is_frozen");
    }

    /**
     * A discarding write by a {@code write} caller answers with the row, not 404.
     *
     * <p>The row is {@code discarded} afterwards, which the read-visibility filter hides from
     * {@code write}; a response built by re-reading through that filter reported failure for a
     * write that had irreversibly succeeded.
     */
    @Test
    void discardingYourOwnDraftAnswersWithTheDiscardedRow() {
        String draftId = submitDraft("to discard", null);

        HttpResponse<String> discarded = send(request(path("/versions/discard"), token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("version", "draft:" + draftId))))
                .build());

        assertThat(discarded.statusCode()).as(discarded.body()).isEqualTo(200);
        JsonNode version = JSON.readTree(discarded.body()).get("version");
        assertThat(version.get("status").asText()).isEqualTo("discarded");
        assertThat(version.get("pin").asText())
                .as("the row keeps the address it was written and read by")
                .isEqualTo("draft:" + draftId);

        // The same discard again is the same intent reached twice, and still a success.
        HttpResponse<String> again = send(request(path("/versions/discard"), token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("version", "draft:" + draftId))))
                .build());
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
    }

    @Test
    void theActionsThatChangeWhatCallersSeeHaveNoRouteHere() {
        String draftId = submitDraft("v", null);

        for (String verb : new String[] {"publish", "rollback", "revoke", "restore"}) {
            HttpResponse<String> response = send(request(
                    path("/versions/" + verb), token)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json(Map.of("version", "draft:" + draftId))))
                    .build());
            assertThat(response.statusCode())
                    .as("%s must be confirmed by a person in the browser (ADR 0031/0061)", verb)
                    .isEqualTo(404);
        }
    }

    @Test
    void theConsumptionListCarriesNoteStateAndStatusButNeverTheDraft() {
        String draftId = submitDraft("v1", "一句话说明");
        publish(draftId, "1.0.0");
        submitDraft("v2 draft", null);

        JsonNode body = JSON.readTree(get("/api/v1/skills/" + username
                + "/pdf-tools/versions", token).body());

        assertThat(body.get("versions")).hasSize(1);
        JsonNode row = body.get("versions").get(0);
        assertThat(row.get("note").asText()).isEqualTo("一句话说明");
        assertThat(row.get("state").asText()).isEqualTo("published");
        assertThat(row.get("status").asText()).isEqualTo("live");
        assertThat(body.toString()).doesNotContain("v2 draft");
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private String path(String suffix) {
        return "/api/v1/author/skills/" + username + "/pdf-tools" + suffix;
    }

    private String submitDraft(String versionName, String note) {
        // A per-name file so two calls are two distinct contents: identical bytes are a replay of
        // the row already there (ADR 0005), which is a different case from a second draft.
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(Map.of(
                        "SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n",
                        "references/" + versionName + ".md", versionName)))
                .field("name", versionName);
        if (note != null) {
            multipart.field("note", note);
        }
        HttpResponse<String> response = send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
        assertThat(response.statusCode()).as("submit failed: %s", response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("version").get("draft_id").asText();
    }

    private void publish(String draftId, String releaseName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", "draft:" + draftId);
        body.put("release_name", releaseName);
        HttpResponse<String> response = webPost("/web/skills/" + username + "/pdf-tools/publish",
                json(body));
        assertThat(response.statusCode()).as("publish failed: %s", response.body()).isEqualTo(200);
    }
}
