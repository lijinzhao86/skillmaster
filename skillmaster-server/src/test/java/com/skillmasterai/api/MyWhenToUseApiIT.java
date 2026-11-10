package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The caller's own when-to-use sentence (v59): writing, reading, limits, the library gate, and the
 * fact that it survives leaving the library.
 */
@Sql("/sql/truncate-business-tables.sql")
class MyWhenToUseApiIT extends AbstractOrganizeIT {

    private static final String PATH = "/api/v1/skills/demo/pdf-tools/my-when-to-use";

    @Test
    void writingReadingReplacingAndClearingNeverTouchTheSkill() {
        String skillId = publicSkill("pdf-tools");
        String bearer = token();
        Map<String, Object> before = skillRow(skillId);

        HttpResponse<String> empty = get(PATH, bearer);
        assertThat(empty.statusCode()).as(empty.body()).isEqualTo(200);
        assertThat(json(empty).get("when_to_use").isNull()).isTrue();

        HttpResponse<String> written = putJson(PATH, bearer,
                "{\"when_to_use\":\"  当我说\\n循环审计时用它 \"}");
        assertThat(written.statusCode()).as(written.body()).isEqualTo(200);
        assertThat(changed(written)).isTrue();

        HttpResponse<String> read = get(PATH, bearer);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(json(read).get("when_to_use").asText())
                .as("trimmed, and the newline folded into one space")
                .isEqualTo("当我说 循环审计时用它");

        HttpResponse<String> again = putJson(PATH, bearer,
                "{\"when_to_use\":\"当我说 循环审计时用它\"}");
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(changed(again)).isFalse();

        HttpResponse<String> replaced = putJson(PATH, bearer,
                "{\"when_to_use\":\"当我说反复审查时用它\"}");
        assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(200);
        assertThat(changed(replaced)).isTrue();

        assertThat(auditRows("my_when_to_use_set"))
                .as("the no-op did not write a second audit row").isEqualTo(2);
        assertThat(skillRow(skillId))
                .as("the private sentence never changes the skill or its versions")
                .isEqualTo(before);
        assertThat(count("SELECT count(*) FROM skill_version")).isZero();

        HttpResponse<String> cleared = delete(PATH, bearer);
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        assertThat(changed(cleared)).isTrue();
        assertThat(delete(PATH, bearer).statusCode()).isEqualTo(200);
        assertThat(changed(delete(PATH, bearer))).isFalse();
        assertThat(auditRows("my_when_to_use_clear")).isEqualTo(1);
        assertThat(json(get(PATH, bearer)).get("when_to_use").isNull()).isTrue();
    }

    @Test
    void aVisibleSkillOutsideTheLibraryIsA403WithTheJoinStep() {
        publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "pdf-tools");
        skill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "hidden", "private",
                com.skillmasterai.common.Ulid.generate(), AT, null, null);
        String bearer = token();

        HttpResponse<String> write = putJson(
                "/api/v1/skills/other/pdf-tools/my-when-to-use", bearer,
                "{\"when_to_use\":\"循环审计\"}");
        assertThat(write.statusCode()).as(write.body()).isEqualTo(403);
        assertThat(json(write).get("error").get("code").asText()).isEqualTo("forbidden");
        assertThat(issue(write)).isEqualTo("not_in_library");
        assertThat(json(write).get("error").get("message").asText()).contains("skill join");

        HttpResponse<String> read = get(
                "/api/v1/skills/other/pdf-tools/my-when-to-use", bearer);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(403);
        assertThat(issue(read)).isEqualTo("not_in_library");

        assertThat(get("/api/v1/skills/other/hidden/my-when-to-use", bearer).statusCode())
                .as("an invisible skill is a 404, as every private read is")
                .isEqualTo(404);
    }

    @Test
    void aDormantEntryIsReadableButNotWritable() {
        String joined = skill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "joined", "public",
                com.skillmasterai.common.Ulid.generate(), AT, null, null);
        join(DEMO_USER_ID, joined);
        String bearer = token();

        HttpResponse<String> read = get(
                "/api/v1/skills/other/joined/my-when-to-use", bearer);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(json(read).get("when_to_use").isNull()).isTrue();

        HttpResponse<String> write = putJson(
                "/api/v1/skills/other/joined/my-when-to-use", bearer,
                "{\"when_to_use\":\"循环审计\"}");
        assertThat(write.statusCode()).as(write.body()).isEqualTo(409);
        assertThat(json(write).get("error").get("code").asText())
                .isEqualTo("library_entry_dormant");
    }

    @Test
    void theLimitIsTwoHundredUnicodeCodePoints() {
        publicSkill("pdf-tools");
        String bearer = token();

        assertThat(putJson(PATH, bearer, withText("汉".repeat(200))).statusCode()).isEqualTo(200);
        HttpResponse<String> overChinese = putJson(PATH, bearer, withText("汉".repeat(201)));
        assertThat(overChinese.statusCode()).as(overChinese.body()).isEqualTo(400);
        assertThat(json(overChinese).get("error").get("code").asText())
                .isEqualTo("my_when_to_use_too_long");
        JsonNode detail = json(overChinese).get("error").get("details").get(0);
        assertThat(detail.get("field").asText()).isEqualTo("when_to_use");
        assertThat(detail.get("issue").asText()).isEqualTo("too_long");
        assertThat(detail.get("actual_code_points").asLong()).isEqualTo(201);
        assertThat(detail.get("limit_code_points").asLong()).isEqualTo(200);
        assertThat(detail.get("actual_bytes")).as("a text limit is not a byte limit").isNull();

        assertThat(putJson(PATH, bearer, withText("😀".repeat(200))).statusCode())
                .as("an emoji is one code point, not two UTF-16 units").isEqualTo(200);
        HttpResponse<String> overEmoji = putJson(PATH, bearer, withText("😀".repeat(201)));
        assertThat(overEmoji.statusCode()).isEqualTo(400);
        assertThat(json(overEmoji).get("error").get("details").get(0)
                .get("actual_code_points").asLong()).isEqualTo(201);

        HttpResponse<String> empty = putJson(PATH, bearer, withText(""));
        assertThat(empty.statusCode()).as(empty.body()).isEqualTo(400);
        assertThat(json(empty).get("error").get("code").asText())
                .isEqualTo("my_when_to_use_empty");
        HttpResponse<String> blank = putJson(PATH, bearer, withText(" \n\t "));
        assertThat(blank.statusCode()).isEqualTo(400);
        assertThat(json(blank).get("error").get("code").asText())
                .isEqualTo("my_when_to_use_empty");
    }

    @Test
    void leavingTheLibraryKeepsTheSentenceAndClearingWorksWithoutIt() {
        String joined = skill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "joined", "public",
                com.skillmasterai.common.Ulid.generate(), null, null, null);
        join(DEMO_USER_ID, joined);
        String bearer = token();
        String path = "/api/v1/skills/other/joined/my-when-to-use";

        assertThat(putJson(path, bearer, withText("循环审计")).statusCode()).isEqualTo(200);

        HttpResponse<String> left =
                delete("/api/v1/skills/other/joined/library", bearer);
        assertThat(left.statusCode()).as(left.body()).isEqualTo(200);
        assertThat(changed(left)).isTrue();
        assertThat(get(path, bearer).statusCode()).as("outside the library the note is not read")
                .isEqualTo(403);

        HttpResponse<String> rejoined = post("/api/v1/skills/other/joined/library", bearer);
        assertThat(rejoined.statusCode()).as(rejoined.body()).isEqualTo(200);
        assertThat(json(get(path, bearer)).get("when_to_use").asText())
                .as("the sentence came back with the library entry").isEqualTo("循环审计");

        assertThat(delete("/api/v1/skills/other/joined/library", bearer).statusCode())
                .isEqualTo(200);
        HttpResponse<String> cleared = delete(path, bearer);
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        assertThat(changed(cleared)).as("clearing does not require the library").isTrue();

        assertThat(post("/api/v1/skills/other/joined/library", bearer).statusCode()).isEqualTo(200);
        assertThat(json(get(path, bearer)).get("when_to_use").isNull()).isTrue();
        assertThat(delete(path, bearer).statusCode()).isEqualTo(200);
        assertThat(changed(delete(path, bearer))).isFalse();
    }

    @Test
    void anotherAccountCannotSeeTheSentence() {
        String demoBearer = token();
        String joined = skill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "joined", "public",
                com.skillmasterai.common.Ulid.generate(), null, null, null);
        join(DEMO_USER_ID, joined);
        String path = "/api/v1/skills/other/joined/my-when-to-use";
        assertThat(putJson(path, demoBearer, withText("循环审计")).statusCode()).isEqualTo(200);

        String otherId = registerAndSignIn("other-person", randomPhone());
        String otherBearer = tokenFor(otherId);
        assertThat(post("/api/v1/skills/other/joined/library", otherBearer).statusCode())
                .isEqualTo(200);

        assertThat(json(get(path, otherBearer)).get("when_to_use").isNull())
                .as("the other person has the skill in their library, but not the sentence")
                .isTrue();
        assertThat(json(get(path, demoBearer)).get("when_to_use").asText())
                .isEqualTo("循环审计");
    }

    @Test
    void theBrowserPlaneUsesTheSameUseCase() {
        publicSkill("pdf-tools");
        token();
        var uri = uri("/web/skills/demo/pdf-tools/my-when-to-use");

        HttpResponse<String> written = browser.request(HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header(com.skillmasterai.support.Browser.CSRF_HEADER, csrfToken())
                .PUT(HttpRequest.BodyPublishers.ofString(withText("循环审计"))));
        assertThat(written.statusCode()).as(written.body()).isEqualTo(200);
        assertThat(changed(written)).isTrue();
        assertThat(json(webGet("/web/skills/demo/pdf-tools/my-when-to-use"))
                .get("when_to_use").asText()).isEqualTo("循环审计");

        HttpResponse<String> cleared = browser.delete(uri);
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        assertThat(changed(cleared)).isTrue();
    }

    private static String withText(String whenToUse) {
        return "{\"when_to_use\":" + JSON.writeValueAsString(whenToUse) + "}";
    }

    private Map<String, Object> skillRow(String skillId) {
        return jdbc.sql("""
                SELECT description, when_to_use, current_version_id FROM skill WHERE id = :id
                """).param("id", skillId).query().singleRow();
    }
}
