package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * A draft's comparison follows the draft's content, not the address it is spelled with.
 *
 * <p>The property worth its own class: a draft is edited in place and keeps its row id as its address
 * (ADR 0063), so any cache in the diff path has to key on something that moves with the content. In
 * production it did not, and the summary kept reporting the file count from before the last edit
 * while {@code --file} — a different key — showed the new one (2026-11-04). Reading the same
 * comparison twice, with a write in between, is the whole test.
 *
 * <p>{@link DraftFileIT} would have been the natural home; it is at its line limit, and this is a
 * different question from "what a write does" — it is "what the comparison reports afterwards".
 */
@Sql("/sql/truncate-business-tables.sql")
class DraftDiffIT extends AbstractAccountIT {

    private String username;
    private String token;

    @BeforeEach
    void signUp() {
        username = randomUsername();
        token = tokenFor(registerAndSignIn(username, randomPhone()));
    }

    @Test
    void aDiffOfADraftIsRecalculatedAfterTheWrite() {
        String code = submitDraft(Map.of("references/a.md", "one\n"));

        JsonNode before = diffOf(code);
        assertThat(before.get("from").isNull())
                .as("nothing is published yet, so every file is an addition").isTrue();
        assertThat(before.get("files")).hasSize(2);

        HttpResponse<String> written = put(code, "references/b.md", "two\n", listEtag(code));
        assertThat(written.statusCode()).as(written.body()).isEqualTo(200);

        assertThat(diffOf(code).get("files"))
                .as("the second comparison is of the draft as it now reads")
                .hasSize(3);
    }

    @Test
    void anEditedDraftDoesNotAnswerAStaleConditionalRequestWith304() {
        String code = submitDraft(Map.of("references/a.md", "one\n"));

        HttpResponse<String> first = diffRequest(code, null);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        String validator = first.headers().firstValue(HttpHeaders.ETAG).orElseThrow();

        assertThat(diffRequest(code, validator).statusCode())
                .as("nothing changed, so the validator still describes this answer — without this "
                        + "half, a validator that never matches would pass the assertions below")
                .isEqualTo(304);

        assertThat(put(code, "references/b.md", "two\n", listEtag(code)).statusCode()).isEqualTo(200);

        HttpResponse<String> revalidated = diffRequest(code, validator);
        assertThat(revalidated.statusCode())
                .as("the answer changed with the draft, so its old validator must not still have it")
                .isEqualTo(200);
        assertThat(JSON.readTree(revalidated.body()).get("files"))
                .as("and the body is the draft as it now reads")
                .hasSize(3);
    }

    /* --------------------------------------------------------------- helpers */

    private String submitDraft(Map<String, String> extra) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n");
        files.putAll(extra);
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(files))
                .field("name", "diff draft");
        HttpResponse<String> response = send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher()).build());
        assertThat(response.statusCode()).as("submit failed: %s", response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("version").get("draft_id").asText();
    }

    private String versionPath(String code, String suffix) {
        return "/api/v1/author/skills/" + username + "/pdf-tools/versions/" + code + suffix;
    }

    private JsonNode diffOf(String code) {
        HttpResponse<String> response = diffRequest(code, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> diffRequest(String code, String ifNoneMatch) {
        HttpRequest.Builder request = request(versionPath(code, "/diff"), token);
        if (ifNoneMatch != null) {
            request.header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        return send(request.GET().build());
    }

    private HttpResponse<String> put(String code, String relpath, String content, String ifMatch) {
        return send(request(versionPath(code, "/files/" + relpath), token)
                .header(HttpHeaders.IF_MATCH, ifMatch)
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(
                        content.getBytes(StandardCharsets.UTF_8)))
                .build());
    }

    private String listEtag(String code) {
        HttpResponse<String> listed =
                send(request(versionPath(code, "/files"), token).GET().build());
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        return listed.headers().firstValue(HttpHeaders.ETAG).orElseThrow();
    }
}
