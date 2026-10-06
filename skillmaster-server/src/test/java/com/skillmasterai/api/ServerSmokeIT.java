package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The whole loop, in the order two real clients walk it: submit over the API, publish in a browser,
 * then find it, look at it and read it over the API again.
 *
 * <p>Every other test in this package isolates one thing — this one exists because the failures that
 * matter are the ones at the seams. A digest computed one way when storing and another way when
 * serving, a manifest ordered differently in two places, a body reformatted between the archive and
 * the response: each of those passes every unit test and breaks only when the pieces are used in
 * sequence.
 *
 * <p><strong>It crosses the planes, and since ADR 0031 that is not avoidable.</strong> Submitting and
 * publishing are two acts by the same person on two different credentials, so this is also the only
 * test that establishes what the two have to agree about: the name a submission lands under, the
 * namespace it lands in, and the fact that nothing at all is readable in between. It therefore signs
 * up a real account rather than using the seeded one — {@code AbstractIT.token()} and the browser
 * flows in {@code AbstractAccountIT} act as two different people, and these have to be one.
 *
 * <p>It is also the regression test for the manual walk-through in {@code README.md}. That file
 * promises a sequence of commands works on a fresh server; if this test passes, the sequence's shape
 * is still right, and the README explains how to run it by hand against a real one.
 */
@Sql("/sql/truncate-business-tables.sql")
class ServerSmokeIT extends AbstractAccountIT {

    /** Non-ASCII in both the frontmatter and the body, because that is the real corpus (§1.6). */
    private static final String SKILL_MD = """
            ---
            name: feishu-tasks
            description: 飞书任务：查询与创建
            metadata:
              platform_api_version: "1"
            ---
            # 飞书任务

            用之前先读 `references/fields.md`。
            """;

    private static final String FIELDS_MD = "# 字段\n\n- 标题\n- 截止时间\n";

    @Test
    void aClientCanSubmitOneSkillAndReadItBackAtEveryLevelOnceSomebodyPublishesIt() {
        // One person, two credentials: a session for the browser and a token for the API. Both come
        // from real endpoints; see AbstractIT.tokenFor.
        String username = randomUsername();
        String token = tokenFor(registerAndSignIn(username, randomPhone()));
        String address = "/api/v1/skills/" + username + "/feishu-tasks";

        // The API plane, the way a CLI does it: the content arrives and stays a draft.
        HttpResponse<String> submitted = submit(token);
        assertThat(submitted.statusCode()).as("submit failed: %s", submitted.body()).isEqualTo(201);
        JsonNode submitBody = JSON.readTree(submitted.body());
        String id = submitBody.get("id").asText();
        String digest = submitBody.get("version").get("digest").asText();
        int number = submitBody.get("version").get("number").asInt();

        // Nothing a consumer can reach yet — the point of the split, asserted before anything else
        // so that a later failure cannot be mistaken for it.
        assertThat(JSON.readTree(get("/api/v1/skills?q=%E9%A3%9E%E4%B9%A6", token).body())
                .get("skills"))
                .as("a draft is not a search result")
                .isEmpty();
        assertThat(get(address, token).statusCode())
                .as("and its address resolves to nothing")
                .isEqualTo(404);

        // The browser plane, the way a person does it. The only thing that makes any of the rest
        // readable.
        HttpResponse<String> published = webPost(
                "/web/skills/" + username + "/feishu-tasks/publish",
                json(Map.of("number", number)));
        assertThat(published.statusCode()).as("publish failed: %s", published.body()).isEqualTo(200);
        assertThat(JSON.readTree(published.body()).get("state").asText()).isEqualTo("published");

        // L1, as a search result: names and descriptions, nothing else.
        JsonNode found = JSON.readTree(
                get("/api/v1/skills?q=%E9%A3%9E%E4%B9%A6", token).body()).get("skills");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("id").asText()).isEqualTo(id);
        assertThat(found.get(0).get("version").get("digest").asText())
                .as("the listing and the submission agree on what is current")
                .isEqualTo(digest);
        assertThat(found.get(0).get("version").get("number").asInt())
                .as("a card carries the version too, so one search is enough to pin (ADR 0012)")
                .isEqualTo(number);

        // L1, as a detail: the whole manifest and no content.
        JsonNode detail = JSON.readTree(get(address, token).body());
        assertThat(detail.get("name").asText()).isEqualTo("feishu-tasks");
        assertThat(detail.get("files")).hasSize(2);
        assertThat(detail.get("frontmatter").get("metadata").get("platform_api_version").asText())
                .as("unknown fields survive the round trip (§3.3)")
                .isEqualTo("1");
        assertThat(detail.toString())
                .as("detail is the manifest; content comes from L2 and L3 only")
                .doesNotContain("用之前先读")
                .doesNotContain("截止时间");

        // L2 and L3 by the URIs the manifest advertised, not by paths assembled here. That is how a
        // real client is told to work — enter once, then follow the addresses you were handed — and
        // it is the only way the manifest's promise is actually exercised: if the routes and the
        // advertised URIs ever drifted apart, every other test would stay green.
        String bodyUri = detail.get("resources").get("body").asText();
        assertThat(bodyUri)
                .as("the version the address did not name is resolved and written into the URI")
                .isEqualTo("/api/v1/skills/" + username + "/feishu-tasks@" + number + "/body");
        assertThat(new String(getBytes(bodyUri, token).body(), StandardCharsets.UTF_8))
                .as("byte for byte what was uploaded — ADR 0005's digest describes these bytes")
                .isEqualTo(SKILL_MD);

        String fieldsUri = uriOf(detail, "references/fields.md");
        assertThat(fieldsUri).isEqualTo(
                "/api/v1/skills/" + username + "/feishu-tasks@" + number
                        + "/files/references/fields.md");
        assertThat(new String(getBytes(fieldsUri, token).body(), StandardCharsets.UTF_8))
                .isEqualTo(FIELDS_MD);

        // And out again.
        assertThat(send(request(address, token).DELETE().build()).statusCode()).isEqualTo(204);
        assertThat(get(address, token).statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(get("/api/v1/skills?q=%E9%A3%9E%E4%B9%A6", token).body())
                .get("skills"))
                .as("a deleted skill is gone from search too, not only from detail")
                .isEmpty();
    }

    @Test
    void theDigestIsStableAcrossTwoSubmissions() {
        // The property a client depends on to decide whether anything changed. It is checked here
        // at the end of the loop rather than only at submit time, because a digest is only useful if
        // the same bytes produce the same value on two different days.
        String token = tokenFor(registerAndSignIn(randomUsername(), randomPhone()));

        assertThat(digestOf(submit(token))).isEqualTo(digestOf(submit(token)));
        assertThat(count("SELECT count(*) FROM skill_version"))
                .as("and identical content is still one version (ADR 0005)")
                .isEqualTo(1);
    }

    /** The pinned URI the manifest advertises for one file — what a client is told to fetch. */
    private static String uriOf(JsonNode detail, String relpath) {
        for (JsonNode file : detail.get("files")) {
            if (file.get("relpath").asText().equals(relpath)) {
                return file.get("uri").asText();
            }
        }
        throw new AssertionError(relpath + " is not in the manifest: " + detail);
    }

    /**
     * One multipart, used for both its parts — the header and the body.
     *
     * <p>Building it twice would be a bug that reads as a server problem: the boundary lives in the
     * instance, so a content type from one and a body from another do not match, and the answer is
     * "the request is missing the 'file' part".
     */
    private HttpResponse<String> submit(String token) {
        Multipart multipart = zip();
        return send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }

    private static String digestOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("submit failed: %s", response.body()).isIn(200, 201);
        return JSON.readTree(response.body()).get("version").get("digest").asText();
    }

    /** A zip holding one skill, wrapped in a directory named after it. */
    private static Multipart zip() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("feishu-tasks/SKILL.md", SKILL_MD);
        files.put("feishu-tasks/references/fields.md", FIELDS_MD);
        return Multipart.create().file("file", "feishu-tasks.zip", Zips.ofText(files));
    }
}
