package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractIT;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole loop, in the order a real client walks it: publish, find it, look at it, read it.
 *
 * <p>Every other test in this package isolates one thing — this one exists because the failures
 * that matter are the ones at the seams. A digest that is computed one way when storing and another
 * way when serving, a manifest ordered differently in two places, a body that is reformatted
 * somewhere between the archive and the response: each of those passes every unit test and breaks
 * only when the pieces are used in sequence.
 *
 * <p>It is also the regression test for the manual walk-through in {@code README.md}. That file
 * promises a sequence of commands works on a fresh server; if this test passes, the sequence's
 * shape is still right, and the README explains how to run it by hand against a real one.
 */
@Sql("/sql/truncate-business-tables.sql")
class ServerSmokeIT extends AbstractIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    void aClientCanPublishOneSkillAndReadItBackAtEveryLevel() {
        HttpResponse<String> published = publish();
        assertThat(published.statusCode()).as("publish failed: %s", published.body()).isEqualTo(201);
        JsonNode publishBody = JSON.readTree(published.body());
        String id = publishBody.get("id").asText();
        String digest = publishBody.get("version").get("digest").asText();

        // L1, as a search result: names and descriptions, nothing else.
        JsonNode found = JSON.readTree(
                get("/api/v1/skills?q=%E9%A3%9E%E4%B9%A6", token()).body()).get("skills");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("id").asText()).isEqualTo(id);
        assertThat(found.get(0).get("version").get("digest").asText())
                .as("the listing and the publish response agree on what is current")
                .isEqualTo(digest);
        assertThat(found.get(0).get("version").get("number").asInt())
                .as("a card carries the version too, so one search is enough to pin (ADR 0012)")
                .isEqualTo(publishBody.get("version").get("number").asInt());

        // L1, as a detail: the whole manifest and no content.
        JsonNode detail = JSON.readTree(get("/api/v1/skills/demo/feishu-tasks", token()).body());
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
                .isEqualTo("/api/v1/skills/demo/feishu-tasks@1/body");
        assertThat(new String(getBytes(bodyUri, token()).body(), StandardCharsets.UTF_8))
                .as("byte for byte what was uploaded — ADR 0005's digest describes these bytes")
                .isEqualTo(SKILL_MD);

        String fieldsUri = uriOf(detail, "references/fields.md");
        assertThat(fieldsUri).isEqualTo("/api/v1/skills/demo/feishu-tasks@1/files/references/fields.md");
        assertThat(new String(getBytes(fieldsUri, token()).body(), StandardCharsets.UTF_8))
                .isEqualTo(FIELDS_MD);

        // And out again.
        assertThat(send(request("/api/v1/skills/demo/feishu-tasks", token()).DELETE().build())
                .statusCode())
                .isEqualTo(204);
        assertThat(get("/api/v1/skills/demo/feishu-tasks", token()).statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(get("/api/v1/skills?q=%E9%A3%9E%E4%B9%A6", token()).body())
                .get("skills"))
                .as("a deleted skill is gone from search too, not only from detail")
                .isEmpty();
    }

    @Test
    void theDigestIsStableAcrossAPublishAndARepublish() {
        // The property a client depends on to decide whether anything changed. It is checked here
        // at the end of the loop rather than only at publish time, because a digest is only useful
        // if the same bytes produce the same value on two different days.
        String first = JSON.readTree(publish().body()).get("version").get("digest").asText();
        String second = JSON.readTree(publish().body()).get("version").get("digest").asText();

        assertThat(second).isEqualTo(first);
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

    private HttpResponse<String> publish() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("feishu-tasks/SKILL.md", SKILL_MD);
        files.put("feishu-tasks/references/fields.md", FIELDS_MD);
        Multipart multipart = Multipart.create().file("file", "feishu-tasks.zip",
                Zips.ofText(files));
        return send(request("/api/v1/skills", token())
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }
}
