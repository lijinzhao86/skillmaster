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
 * The version type (v18, ADR 0091): declared at submission, changed while a draft, frozen at publish.
 *
 * <p><strong>Both planes, because both write it.</strong> The console talks to {@code /web/...} with
 * a session and the CLI to {@code /api/v1/author/...} with a key; the field is one column, and a
 * route missing on either side leaves one of the two surfaces unable to say what it means. The tests
 * here therefore set it on one plane and read it back on the other.
 *
 * <p><strong>The rung is {@code write}, not the name's rung.</strong> A name and a note are labels
 * somebody wrote, so they take the submitter or {@code maintain}; a type only declares how big the
 * change is, so anybody who may add a draft may declare it — which is what T02 asks.
 */
@Sql("/sql/truncate-business-tables.sql")
class ReleaseTypeIT extends AbstractAccountIT {

    private String username;
    private String token;

    @BeforeEach
    void signUp() {
        username = randomUsername();
        token = tokenFor(registerAndSignIn(username, randomPhone()));
    }

    @Test
    void aDraftDeclaresItsTypeAndTheNextSubmissionDoesNotInheritIt() {
        String first = submitDraft("第一版", "minor");

        assertThat(typeOf(first)).as("the submission carried it").isEqualTo("minor");

        // **不做继承** (ADR 0091 决定 9): a type is a judgement about *this* change, and the previous
        // judgement is not evidence for this one.
        String second = submitDraft("第二版", null);
        assertThat(typeOf(second)).as("a submission without a type has none").isNull();
        assertThat(typeOf(first)).as("and the older draft keeps its own").isEqualTo("minor");
    }

    @Test
    void aTypeCanBeChangedAndClearedWhileTheDraftIsADraft() {
        String draft = submitDraft("第一版", "minor");

        assertThat(patchType(draft, Map.of("release_type", "patch")).statusCode()).isEqualTo(200);
        assertThat(typeOf(draft)).as("改档是写回服务端的，不是客户端状态").isEqualTo("patch");

        Map<String, Object> cleared = new LinkedHashMap<>();
        cleared.put("release_type", null);
        assertThat(patchType(draft, cleared).statusCode()).isEqualTo(200);
        assertThat(typeOf(draft)).as("null means 「没有类型」").isNull();

        // **The word `none` means the same thing** (T01): a form or the CLI cannot always send a
        // JSON null, so the server takes the word and stores the absence, not the string.
        assertThat(patchType(draft, Map.of("release_type", "minor")).statusCode()).isEqualTo(200);
        assertThat(patchType(draft, Map.of("release_type", "none")).statusCode()).isEqualTo(200);
        assertThat(typeOf(draft)).as("「none」 is cleared, not stored as a type").isNull();
    }

    @Test
    void aTypeFreezesAtPublish() {
        String draft = submitDraft("第一版", "minor");
        publish(draft);

        HttpResponse<String> late = patchType(draft, Map.of("release_type", "major"));
        assertThat(late.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(late.body()).get("error").get("code").asText())
                .isEqualTo("release_type_is_frozen");
    }

    @Test
    void anUnknownTypeIsABadFieldRatherThanABadRequest() {
        String draft = submitDraft("第一版", null);

        HttpResponse<String> response = patchType(draft, Map.of("release_type", "huge"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("error").get("code").asText())
                .isEqualTo("invalid_request");
        assertThat(field(response)).isEqualTo("release_type");
    }

    @Test
    void theWriteRungIsEnoughAndBrowseIsNot() {
        // The owner's draft, shared down to two other people: one at `write`, one at `browse`.
        String draft = submitDraft("第一版", "minor");
        String writePhone = randomPhone();
        String writer = randomUsername();
        String writerToken = tokenFor(registerAndSignIn(writer, writePhone));
        String readerPhone = randomPhone();
        String reader = randomUsername();
        String readerToken = tokenFor(registerAndSignIn(reader, readerPhone));
        // The shared jar is the reader's now; the owner acts with the browser again below.
        assertThat(grant(writer, "write").statusCode()).isEqualTo(201);
        assertThat(grant(reader, "browse").statusCode()).isEqualTo(201);

        HttpResponse<String> byWriter = send(request(authorPath("versions/" + draft
                + "/release-type"), writerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        json(Map.of("release_type", "major"))))
                .build());
        assertThat(byWriter.statusCode())
                .as("write may declare the size of a change: %s", byWriter.body())
                .isEqualTo(200);

        HttpResponse<String> byReader = send(request(authorPath("versions/" + draft
                + "/release-type"), readerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        json(Map.of("release_type", "patch"))))
                .build());
        // **404, not 403, and it is the suite's one-404 rule rather than an oversight.** A draft is
        // visible to `write` and above only (`visibleStates`), so for somebody holding `browse` this
        // version does not exist — the same answer every author-plane route gives for a row the
        // caller cannot see. A 403 here would tell a stranger that a draft exists.
        assertThat(byReader.statusCode()).as("browse cannot see a draft at all: %s", byReader.body())
                .isEqualTo(404);
        assertThat(typeOf(draft)).as("and the refusal changed nothing").isEqualTo("major");

        // The other half of T02: `write` may declare how big a change is, and still may not publish —
        // that is `maintain`'s, because publishing changes what everybody reads.
        Map<String, Object> publish = new LinkedHashMap<>();
        publish.put("version", draft);
        HttpResponse<String> notTheirsToPublish = send(request("/web/skills/" + username
                + "/pdf-tools/publish", writerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("X-XSRF-TOKEN", csrfToken())
                .POST(HttpRequest.BodyPublishers.ofString(json(publish)))
                .build());
        assertThat(notTheirsToPublish.statusCode())
                .as("write is not enough to publish: %s", notTheirsToPublish.body())
                .isIn(401, 403);
    }

    @Test
    void theBrowserPlaneWritesTheSameField() {
        String draft = submitDraft("第一版", null);

        HttpResponse<String> response = webPatch(
                "/web/skills/" + username + "/pdf-tools/versions/" + draft + "/release-type",
                Map.of("release_type", "patch"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).get("release_type").asText()).isEqualTo("patch");
        // Read back over the *other* plane: one column, two ways in.
        assertThat(typeOf(draft)).isEqualTo("patch");
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private String authorPath(String suffix) {
        return "/api/v1/author/skills/" + username + "/pdf-tools/" + suffix;
    }

    /** The draft's type, read from the author list — the surface the CLI prints. */
    private String typeOf(String draftCode) {
        HttpResponse<String> response = get(authorPath("versions"), token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        for (JsonNode row : JSON.readTree(response.body()).get("versions")) {
            if (row.get("code").asText().equals(draftCode)) {
                JsonNode type = row.get("release_type");
                return type == null || type.isNull() ? null : type.asText();
            }
        }
        throw new AssertionError("no version row " + draftCode + " in " + response.body());
    }

    private HttpResponse<String> patchType(String draftCode, Map<String, ?> body) {
        return send(request(authorPath("versions/" + draftCode + "/release-type"), token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json(body)))
                .build());
    }

    /** A PATCH with the session's CSRF pair — the browser plane's shape. */
    private HttpResponse<String> webPatch(String path, Map<String, ?> body) {
        return browser.patch(uri(path), json(body));
    }

    private HttpResponse<String> grant(String handle, String role) {
        return send(request("/api/v1/skills/" + username + "/pdf-tools/grants", token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("handle", handle, "role", role))))
                .build());
    }

    /** A submission, with the type when one is given. */
    private String submitDraft(String versionName, String releaseType) {
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(Map.of(
                        "SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n",
                        "references/" + versionName + ".md", versionName)))
                .field("name", versionName);
        if (releaseType != null) {
            multipart.field("release_type", releaseType);
        }
        HttpResponse<String> response = send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
        assertThat(response.statusCode()).as("submit failed: %s", response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("version").get("draft_id").asText();
    }

    private void publish(String draftCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", draftCode);
        body.put("release_name", "1.0.0");
        HttpResponse<String> response = webPost(
                "/web/skills/" + username + "/pdf-tools/publish", json(body));
        assertThat(response.statusCode()).as("publish failed: %s", response.body()).isEqualTo(200);
    }
}
