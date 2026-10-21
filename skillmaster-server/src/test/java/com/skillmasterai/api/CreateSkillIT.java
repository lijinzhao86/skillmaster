package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * Creating a skill and its first draft in one action (ADR 0070).
 *
 * <p>What is asserted is the whole promise: the skill exists, it already has one draft with a real
 * {@code SKILL.md}, the list says "draft", and neither refusal leaves a row behind.
 */
@Sql("/sql/truncate-business-tables.sql")
class CreateSkillIT extends AbstractAccountIT {

    private static final String BASE = "/web/skills";

    private String username;

    @BeforeEach
    void signUp() {
        username = randomUsername();
        tokenFor(registerAndSignIn(username, randomPhone()));
    }

    @Test
    void createsTheSkillAndItsFirstDraftInOneRequest() {
        HttpResponse<String> created = create("pdf-tools", "把 PDF 拆页、合并与压缩。", "第一版");

        assertThat(created.statusCode()).as("create failed: %s", created.body()).isEqualTo(201);
        JsonNode version = body(created).get("version");
        String code = version.get("draft_id").asText();
        assertThat(version.get("draft_name").asText()).isEqualTo("第一版");
        assertThat(version.get("state").asText()).isEqualTo("draft");
        assertThat(version.get("file_count").asInt()).isEqualTo(1);

        JsonNode detail = body(webGet(BASE + "/" + username + "/pdf-tools@" + code));
        assertThat(detail.get("files")).hasSize(1);
        assertThat(detail.get("files").get(0).get("relpath").asText()).isEqualTo("SKILL.md");

        JsonNode skill = body(webGet(BASE));
        assertThat(skill.get("skills")).hasSize(1);
        assertThat(skill.get("skills").get(0).get("name").asText()).isEqualTo("pdf-tools");
    }

    @Test
    void anExistingNameIsRefusedWithoutCreatingAnything() {
        assertThat(create("pdf-tools", "one", "第一版").statusCode()).isEqualTo(201);
        long[] before = rows();

        HttpResponse<String> second = create("pdf-tools", "two", "第一版");

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(body(second).get("error").get("code").asText())
                .isEqualTo("skill_already_exists");
        assertThat(count("SELECT COUNT(*) FROM skill WHERE name = 'pdf-tools'")).isEqualTo(1);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void aDeletedNameIsANamedConflict() {
        assertThat(create("pdf-tools", "one", "第一版").statusCode()).isEqualTo(201);
        assertThat(webPost(BASE + "/" + username + "/pdf-tools/delete", "").statusCode())
                .isEqualTo(204);
        long[] before = rows();

        HttpResponse<String> again = create("pdf-tools", "two", "第一版");

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(body(again).get("error").get("code").asText()).isEqualTo("skill_deleted");
        // Zero writes, all three tables (PRD #19), not just the skill row.
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void aMissingNameIsRefusedRatherThanCrashing() {
        // The body is JSON: an omitted key is null, not "", and the template renders before
        // SkillDocument can say which field is missing — so the field has to survive to that point.
        HttpResponse<String> refused = webPost(BASE, json(Map.of(
                "description", "描述", "version_name", "第一版")));

        assertThat(refused.statusCode()).as("body: %s", refused.body()).isEqualTo(400);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("invalid_upload");
        assertThat(body(refused).get("error").get("details").toString())
                .contains("required_field_missing");
        assertThat(count("SELECT COUNT(*) FROM skill")).isZero();
    }

    /** The three tables a create touches, in the order the assertions name them. */
    private long[] rows() {
        return new long[] {
            count("SELECT COUNT(*) FROM skill"),
            count("SELECT COUNT(*) FROM skill_version"),
            count("SELECT COUNT(*) FROM version_file"),
        };
    }

    @Test
    void theNameIsCountedInCodePoints() {
        // Emoji, not CJK: each is two UTF-16 units, so counting units would refuse the first one.
        String sixtyFour = "😀".repeat(64);
        String sixtyFive = "😀".repeat(65);

        assertThat(create(sixtyFour, "描述", "第一版").statusCode()).isEqualTo(201);

        HttpResponse<String> refused = create(sixtyFive, "描述", "第一版");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(body(refused).get("error").get("details").toString())
                .contains("name_too_long");
    }

    private HttpResponse<String> create(String name, String description, String versionName) {
        return webPost(BASE, json(Map.of(
                "name", name, "description", description, "version_name", versionName)));
    }
}
