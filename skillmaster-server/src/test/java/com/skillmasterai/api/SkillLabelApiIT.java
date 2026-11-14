package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * v66 P0: the label read model, the label filter and the vocabulary (test-plan T2–T6, T14).
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillLabelApiIT extends AbstractOrganizeIT {

    @Test
    void cardsAndMetadataCarryOnlyDisplayableOutcomeAndLabels() {
        String skillId = publicSkill("pdf-tools");
        String versionId = currentVersion(skillId);
        versionRow(skillId, versionId, 1);
        outcome(skillId, "一份拆好的 PDF，以及每页对应的原文页码。", "agent", versionId);
        label(skillId, "runtime:python", "auto", versionId);
        label(skillId, "runtime:api-key", "confirmed", versionId);
        label(skillId, "pricing_type:free", "auto", versionId);
        label(skillId, "runtime:prompt-only", "auto", versionId);
        label(skillId, "runtime:node", "pending", versionId);

        String bearer = token();
        HttpResponse<String> cards = get("/api/v1/skills?scope=public&q=pdf-tools", bearer);
        assertThat(cards.statusCode()).as(cards.body()).isEqualTo(200);
        JsonNode card = skills(cards).get(0);
        assertThat(card.get("outcome").asText())
                .isEqualTo("一份拆好的 PDF，以及每页对应的原文页码。");
        assertThat(card.get("labels")).hasSize(2);
        assertThat(card.get("labels").get(0).get("key").asText()).isEqualTo("runtime");
        assertThat(card.get("labels").get(0).get("label").asText()).isEqualTo("Python");
        assertThat(card.get("labels").get(0).get("full").asText()).isEqualTo("需要 Python");
        assertThat(card.get("labels").get(0).get("group").asText()).isEqualTo("language");
        assertThat(card.get("labels").get(1).get("label").asText()).isEqualTo("API key");

        HttpResponse<String> meta = get("/api/v1/skills/demo/pdf-tools/metadata", bearer);
        assertThat(meta.statusCode()).as(meta.body()).isEqualTo(200);
        assertThat(json(meta).get("outcome").asText()).isEqualTo(card.get("outcome").asText());
        assertThat(json(meta).get("labels")).hasSize(2);
        assertThat(json(meta).get("description").asText()).isEqualTo("pdf-tools");
    }

    @Test
    void aLabelFilterIsAndAndAnUnknownWordIsFourHundred() {
        String python = publicSkill("python-only");
        String paid = publicSkill("python-paid");
        String pythonVersion = currentVersion(python);
        String paidVersion = currentVersion(paid);
        versionRow(python, pythonVersion, 1);
        versionRow(paid, paidVersion, 1);
        label(python, "runtime:python", "auto", pythonVersion);
        label(paid, "runtime:python", "auto", paidVersion);
        label(paid, "pricing_type:paid", "auto", paidVersion);
        String bearer = token();

        assertThat(names(skills(get("/api/v1/skills?scope=public&label=runtime:python",
                bearer)))).containsExactlyInAnyOrder("python-only", "python-paid");
        assertThat(names(skills(get("/api/v1/skills?scope=public&label=runtime:python"
                + "&label=pricing_type:paid", bearer)))).containsExactly("python-paid");
        assertThat(names(skills(get("/api/v1/skills?scope=public&q=python"
                + "&label=runtime:python", bearer)))).containsExactlyInAnyOrder("python-only",
                        "python-paid");

        HttpResponse<String> unknown = get("/api/v1/skills?scope=public&label=runtime:nope",
                bearer);
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(json(unknown).get("error").get("code").asText()).isEqualTo("invalid_request");
        assertThat(unknown.body()).contains("runtime:nope");
    }

    @Test
    void theLibraryListingTakesTheSameLabelFilter() {
        // v66 TD §CLI: 「要按属性筛库内 skill，用 skills search --scope library --label ...」. The
        // library enumerates by its own predicates, so the filter has to exist on that path too —
        // and an unknown word is still a 400, not an empty page.
        String mine = publicSkill("mine");
        String version = currentVersion(mine);
        versionRow(mine, version, 1);
        label(mine, "runtime:python", "auto", version);
        String bearer = token();

        assertThat(names(skills(get("/api/v1/skills?scope=library&label=runtime:python", bearer))))
                .containsExactly("mine");
        assertThat(names(skills(get("/api/v1/skills?scope=library&label=runtime:go", bearer))))
                .isEmpty();
        HttpResponse<String> unknown =
                get("/api/v1/skills?scope=library&label=runtime:nope", bearer);
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.body()).contains("runtime:nope");
        assertThat(get("/api/v1/skills?scope=library&show=deleted&label=runtime:python", bearer)
                .statusCode()).isEqualTo(400);
    }

    @Test
    void aDisplayOnlyAttributeIsShownButCannotBeFiltered() {
        // v66 TD: a new dimension may enter the vocabulary as display-only (`filterable=false`).
        // It is drawn on the card with that flag, and asking for it as a filter is still a 400.
        String skillId = publicSkill("pdf-tools");
        String versionId = currentVersion(skillId);
        versionRow(skillId, versionId, 1);
        jdbc.sql("""
                INSERT INTO label_definition
                  (id, dimension_key, dimension_name, value, label_short, label_full, group_key,
                   filterable, state, sort_order, created_at)
                VALUES ('input_type:file', 'input_type', '输入', 'file', '文件', '需要提供文件',
                        'tool', FALSE, 'enabled', 1, :at)
                """).param("at", AT).update();
        labelRow(skillId, "input_type:file", "agent", "confirmed", versionId);

        String bearer = token();
        JsonNode card = skills(get("/api/v1/skills?scope=public&q=pdf-tools", bearer)).get(0);
        assertThat(card.get("labels")).hasSize(1);
        assertThat(card.get("labels").get(0).get("label").asText()).isEqualTo("文件");
        assertThat(card.get("labels").get(0).get("filterable").asBoolean()).isFalse();
        assertThat(get("/api/v1/skills?scope=public&label=input_type:file", bearer)
                .statusCode()).isEqualTo(400);

        jdbc.sql("DELETE FROM skill_label WHERE definition_id = 'input_type:file'").update();
        jdbc.sql("DELETE FROM label_definition WHERE id = 'input_type:file'").update();
    }

    @Test
    void anOperatorRowSpeaksAcrossVersionsOnlyWhenItIsConfirmed() {
        // The read model's rule is two-part: an agent claim is evidence about its version, and
        // only an operator's **confirmed** decision outlives versions. `operator` + `auto` is a
        // half-written decision and must not display.
        String skillId = publicSkill("pdf-tools");
        String versionId = currentVersion(skillId);
        versionRow(skillId, versionId, 1);
        versionRow(skillId, "a-different-version", 2);
        labelRow(skillId, "runtime:python", "operator", "auto", "a-different-version");
        String bearer = token();
        // An omitted field (the server writes no empty array) is the "nothing shows" answer.
        assertThat(skills(get("/api/v1/skills?scope=public&q=pdf-tools", bearer))
                .get(0).has("labels")).isFalse();

        // Nothing about `operator` + `auto` becomes displayable on the current version either:
        // only a confirmed operator claim is a decision, and only an agent claim is version-bound.
        labelRow(skillId, "runtime:python", "operator", "auto", versionId);
        assertThat(skills(get("/api/v1/skills?scope=public&q=pdf-tools", bearer))
                .get(0).has("labels")).isFalse();

        labelRow(skillId, "runtime:python", "operator", "confirmed", "a-different-version");
        assertThat(skills(get("/api/v1/skills?scope=public&q=pdf-tools", bearer))
                .get(0).path("labels")).hasSize(1);
    }

    @Test
    void theVocabularyIsExtensibleAndCarriesNoPublicCount() {
        String bearer = token();
        HttpResponse<String> before = get("/api/v1/labels", bearer);
        assertThat(before.statusCode()).as(before.body()).isEqualTo(200);
        assertThat(json(before)).hasSize(24);
        assertThat(before.body()).doesNotContain("public_total");

        JsonNode python = json(before).valueStream()
                .filter(row -> "runtime:python".equals(row.get("key").asText())).findFirst()
                .orElseThrow();
        assertThat(python.get("short").asText()).isEqualTo("Python");
        assertThat(python.get("full").asText()).isEqualTo("需要 Python");
        assertThat(python.get("group").asText()).isEqualTo("language");

        jdbc.sql("""
                INSERT INTO label_definition
                  (id, dimension_key, dimension_name, value, label_short, label_full, group_key,
                   filterable, state, sort_order, created_at)
                VALUES ('input_type:file', 'input_type', '输入', 'file', '文件', '需要提供文件',
                        'tool', TRUE, 'enabled', 1, :at)
                """).param("at", AT).update();
        HttpResponse<String> after = get("/api/v1/labels", bearer);
        assertThat(json(after)).hasSize(25);
        jdbc.sql("DELETE FROM label_definition WHERE id = 'input_type:file'").update();
    }

    @Test
    void anOutcomeThatEvaluatedAnotherVersionIsNotShown() {
        String skillId = publicSkill("pdf-tools");
        String versionId = currentVersion(skillId);
        versionRow(skillId, versionId, 1);
        versionRow(skillId, "a-different-version", 2);
        outcome(skillId, "旧版本的结论", "agent", "a-different-version");
        String bearer = token();
        assertThat(skills(get("/api/v1/skills?scope=public&q=pdf-tools", bearer))
                .get(0).has("outcome")).isFalse();
        outcome(skillId, "运营确认的结论", "operator", "a-different-version");
        assertThat(skills(get("/api/v1/skills?scope=public&q=pdf-tools", bearer))
                .get(0).get("outcome").asText()).isEqualTo("运营确认的结论");
        assertThat(versionId).isNotBlank();
    }

    private void versionRow(String skillId, String versionId, int number) {
        String name = jdbc.sql("SELECT name FROM skill WHERE id = :id")
                .param("id", skillId).query(String.class).single();
        jdbc.sql("""
                INSERT INTO skill_version
                  (id, skill_id, number, digest, file_count, total_bytes, submitted_by,
                   submitted_at, state, state_at, title, description, frontmatter, version,
                   first_published_at)
                VALUES (:id, :skill, :number, :digest, 0, 0, :user, :at, 'published', :at,
                        :name, :name, '{}', :versionName, :at)
                ON CONFLICT (id) DO NOTHING
                """).param("id", versionId).param("skill", skillId).param("number", number)
                .param("digest", "digest-" + versionId).param("user", DEMO_USER_ID)
                .param("name", name).param("versionName", "1.0." + number)
                .param("at", AT).update();
    }

    private String currentVersion(String skillId) {
        return jdbc.sql("SELECT current_version_id FROM skill WHERE id = :id")
                .param("id", skillId).query(String.class).single();
    }

    private void outcome(String skillId, String text, String source, String versionId) {
        jdbc.sql("""
                UPDATE skill SET outcome = :outcome, outcome_source = :source,
                                 outcome_confidence = 0.95, outcome_evidence = '{}'::jsonb,
                                 outcome_evaluated_version_id = :version,
                                 outcome_updated_at = :at
                 WHERE id = :id
                """).param("outcome", text).param("source", source).param("version", versionId)
                .param("at", AT).param("id", skillId).update();
    }

    private void label(String skillId, String definitionId, String reviewState, String versionId) {
        labelRow(skillId, definitionId, "agent", reviewState, versionId);
    }

    private void labelRow(String skillId, String definitionId, String source, String reviewState,
            String versionId) {
        String id = "lbl-" + skillId + "-" + definitionId + "-" + source + "-" + reviewState;
        jdbc.sql("""
                INSERT INTO skill_label
                  (id, skill_id, definition_id, source, confidence, evidence, vocabulary_version,
                   review_state, evaluated_version_id, created_at, updated_at)
                VALUES (:id, :skill, :definition, :source, 0.95, '{}'::jsonb, 'v66-test',
                        :review, :version, :at, :at)
                ON CONFLICT (skill_id, definition_id) DO UPDATE
                   SET source = EXCLUDED.source, review_state = EXCLUDED.review_state,
                       evaluated_version_id = EXCLUDED.evaluated_version_id
                """).param("id", id).param("skill", skillId).param("definition", definitionId)
                .param("source", source).param("review", reviewState)
                .param("version", versionId).param("at", AT).update();
    }
}
