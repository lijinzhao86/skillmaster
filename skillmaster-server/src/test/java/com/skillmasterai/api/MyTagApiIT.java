package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * Private tags: definitions, attachments, limits and the privacy line (test-plan T12–T14, T17,
 * T18, T26, T27).
 */
@Sql("/sql/truncate-business-tables.sql")
class MyTagApiIT extends AbstractOrganizeIT {

    @Test
    void aDefinitionCanBeCreatedRenamedDescribedAndDeleted() {
        String bearer = token();

        HttpResponse<String> created = postJson("/api/v1/me/tags", bearer,
                "{\"name\":\"客户项目\",\"description\":\"交付期内使用\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id = json(created).get("id").asText();

        JsonNode list = json(get("/api/v1/me/tags", bearer)).get("tags");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("used_in").asLong()).isZero();
        assertThat(list.get(0).get("description").asText()).isEqualTo("交付期内使用");

        HttpResponse<String> renamed = send(request("/api/v1/me/tags/" + id, bearer)
                .header("Content-Type", "application/json")
                .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"name\":\"客户交付\"}"))
                .build());
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
        assertThat(json(renamed).get("name").asText()).isEqualTo("客户交付");

        HttpResponse<String> described = send(request("/api/v1/me/tags/" + id, bearer)
                .header("Content-Type", "application/json")
                .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"description\":\"\"}"))
                .build());
        assertThat(described.statusCode()).as(described.body()).isEqualTo(200);
        assertThat(json(described).get("description").isNull())
                .as("an empty sentence is stored as no sentence").isTrue();

        token();
        HttpResponse<String> deleted = browser.delete(uri("/web/tags/" + id));
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(200);
        assertThat(json(deleted).get("changed").asBoolean())
                .as("deleting a definition always changes something").isTrue();
        assertThat(count("SELECT count(*) FROM my_tag_definition")).isZero();
        assertThat(auditRows("tag_definition_create")).isEqualTo(1);
        assertThat(auditRows("tag_definition_rename")).isEqualTo(1);
        assertThat(auditRows("tag_definition_describe")).isEqualTo(1);
        assertThat(auditRows("tag_definition_delete")).isEqualTo(1);
    }

    @Test
    void namesAreNormalizedAndCollisionsAreRefused() {
        String bearer = token();
        assertThat(postJson("/api/v1/me/tags", bearer, "{\"name\":\"PDF\"}").statusCode())
                .isEqualTo(201);

        HttpResponse<String> duplicate =
                postJson("/api/v1/me/tags", bearer, "{\"name\":\" pdf \"}");
        assertThat(duplicate.statusCode()).isEqualTo(400);
        assertThat(issue(duplicate)).isEqualTo("name_taken");

        HttpResponse<String> shelf = postJson("/api/v1/me/tags", bearer,
                "{\"name\":\"文档与演示\"}");
        assertThat(shelf.statusCode()).isEqualTo(400);
        assertThat(issue(shelf)).isEqualTo("category_name_taken");

        HttpResponse<String> longName = postJson("/api/v1/me/tags", bearer,
                "{\"name\":\"" + "x".repeat(25) + "\"}");
        assertThat(longName.statusCode()).isEqualTo(400);
        assertThat(issue(longName)).isEqualTo("invalid_name");
    }

    @Test
    void theHundredthDefinitionSucceedsAndTheNextIsRefused() {
        for (int i = 0; i < 99; i++) {
            definition(DEMO_USER_ID, "tag-" + i, null);
        }
        String bearer = token();

        assertThat(postJson("/api/v1/me/tags", bearer, "{\"name\":\"hundredth\"}").statusCode())
                .isEqualTo(201);
        HttpResponse<String> over =
                postJson("/api/v1/me/tags", bearer, "{\"name\":\"one-too-many\"}");
        assertThat(over.statusCode()).isEqualTo(400);
        assertThat(issue(over)).isEqualTo("tag_limit_reached");
    }

    @Test
    void attachingCreatesTheDefinitionOnceAndDetachingKeepsIt() {
        publicSkill("pdf-tools");
        String bearer = token();

        HttpResponse<String> attached = postJson(
                "/api/v1/skills/demo/pdf-tools/tags/my", bearer, "{\"name\":\"客户项目\"}");
        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(200);
        assertThat(changed(attached)).isTrue();

        HttpResponse<String> again = postJson(
                "/api/v1/skills/demo/pdf-tools/tags/my", bearer, "{\"name\":\"客户项目\"}");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(changed(again)).isFalse();

        assertThat(count("SELECT count(*) FROM my_tag_definition")).isEqualTo(1);
        JsonNode myTags = json(get("/api/v1/me/tags", bearer)).get("tags");
        assertThat(myTags.get(0).get("used_in").asLong()).isEqualTo(1);

        HttpResponse<String> detached = delete(
                "/api/v1/skills/demo/pdf-tools/tags/my?name=%E5%AE%A2%E6%88%B7%E9%A1%B9%E7%9B%AE",
                bearer);
        assertThat(detached.statusCode()).as(detached.body()).isEqualTo(200);
        assertThat(json(detached).get("changed").asBoolean()).isTrue();
        assertThat(count("SELECT count(*) FROM my_tag_definition"))
                .as("the definition and its sentence stay").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM my_tag_attachment")).isZero();

        HttpResponse<String> missing = delete(
                "/api/v1/skills/demo/pdf-tools/tags/my?name=%E5%AE%A2%E6%88%B7%E9%A1%B9%E7%9B%AE",
                bearer);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("error").get("code").asText())
                .isEqualTo("tag_not_attached");
    }

    @Test
    void twentyEffectiveTagsAreTheCeiling() {
        String skillId = publicSkill("pdf-tools");
        for (int i = 0; i < 20; i++) {
            attach(definition(DEMO_USER_ID, "tag-" + i, null), skillId);
        }
        String bearer = token();

        HttpResponse<String> over = postJson(
                "/api/v1/skills/demo/pdf-tools/tags/my", bearer, "{\"name\":\"twenty-first\"}");
        assertThat(over.statusCode()).isEqualTo(400);
        assertThat(issue(over)).isEqualTo("tag_limit_reached");
        assertThat(count("SELECT count(*) FROM my_tag_attachment"))
                .as("the auto-created definition rolled back with the refusal")
                .isEqualTo(20);
        assertThat(count("SELECT count(*) FROM my_tag_definition")).isEqualTo(20);
    }

    @Test
    void aVisibleSkillOutsideTheLibraryIsA403WithTheWayOut() {
        publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "pdf-tools");
        skill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "offline", "public",
                com.skillmasterai.common.Ulid.generate(), AT, null, null);
        String bearer = token();

        HttpResponse<String> joinable = postJson(
                "/api/v1/skills/other/pdf-tools/tags/my", bearer, "{\"name\":\"x\"}");
        assertThat(joinable.statusCode()).isEqualTo(403);
        assertThat(json(joinable).get("error").get("code").asText()).isEqualTo("forbidden");
        assertThat(json(joinable).get("error").get("details").get(0).get("issue").asText())
                .isEqualTo("not_in_library");
        assertThat(json(joinable).get("error").get("message").asText()).contains("skill join");

        HttpResponse<String> notJoinable = postJson(
                "/api/v1/skills/other/offline/tags/my", bearer, "{\"name\":\"x\"}");
        assertThat(notJoinable.statusCode()).isEqualTo(403);
        assertThat(json(notJoinable).get("error").get("message").asText())
                .contains("加不进库");
    }

    @Test
    void aDormantEntryIsA409NotA403() {
        String joined = skill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "joined", "public",
                com.skillmasterai.common.Ulid.generate(), AT, null, null);
        join(DEMO_USER_ID, joined);
        String shared = skill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "shared", "public",
                com.skillmasterai.common.Ulid.generate(), AT, null, null);
        grant(shared, DEMO_USER_ID, "browse");
        String bearer = token();

        HttpResponse<String> joinedOffline = postJson(
                "/api/v1/skills/other/joined/tags/my", bearer, "{\"name\":\"x\"}");
        assertThat(joinedOffline.statusCode()).isEqualTo(409);
        assertThat(json(joinedOffline).get("error").get("code").asText())
                .isEqualTo("library_entry_dormant");

        HttpResponse<String> sharedOffline = postJson(
                "/api/v1/skills/other/shared/tags/my", bearer, "{\"name\":\"x\"}");
        assertThat(sharedOffline.statusCode())
                .as("shared read-only is in the library; the entry is what is dormant")
                .isEqualTo(409);
    }

    @Test
    void anInvisibleSkillIsA404ForEveryPrivateTagWrite() {
        String bearer = token();
        assertThat(postJson("/api/v1/skills/other/hidden/tags/my", bearer,
                "{\"name\":\"x\"}").statusCode()).isEqualTo(404);
        assertThat(delete("/api/v1/skills/other/hidden/tags/my?name=x", bearer).statusCode())
                .isEqualTo(404);
    }

    @Test
    void theThreeGroupsAreOrderedAndPrivateTagsNeverCrossAccounts() {
        String skillId = publicSkill("pdf-tools");
        jdbc.sql("UPDATE skill SET category_key = 'doc' WHERE id = :id")
                .param("id", skillId).update();
        publicTag(skillId, "pdf", "PDF");
        String bearer = token();
        // Through the API, so the attach writes its audit row.
        assertThat(postJson("/api/v1/skills/demo/pdf-tools/tags/my", bearer,
                "{\"name\":\"客户项目\"}").statusCode()).isEqualTo(200);
        String mine = jdbc.sql("SELECT id FROM my_tag_definition WHERE user_id = :user")
                .param("user", DEMO_USER_ID).query(String.class).single();
        String theirs = definition(OTHER_USER_ID, "别人的标签", null);
        attach(theirs, skillId);

        JsonNode groups = json(get("/api/v1/skills/demo/pdf-tools/tags", bearer));
        assertThat(groups.get("category").get("key").asText()).isEqualTo("doc");
        assertThat(groups.get("public_tags").get(0).get("key").asText()).isEqualTo("pdf");
        assertThat(groups.get("my_tags").get("readable").asBoolean()).isTrue();
        assertThat(groups.get("my_tags").get("tags")).hasSize(1);
        assertThat(groups.get("my_tags").get("tags").get(0).get("name").asText())
                .isEqualTo("客户项目");

        // The audit row names ids only: a private tag's word must not travel.
        String detail = jdbc.sql("""
                SELECT detail FROM audit_event WHERE action = 'tag_my_attach'
                """).query(String.class).single();
        assertThat(detail).contains(mine).doesNotContain("客户项目");
    }

    @Test
    void dormantAttachmentsAreRetainedButNotCounted() {
        String skillId = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "pdf-tools");
        String definition = definition(DEMO_USER_ID, "客户项目", null);
        attach(definition, skillId);
        join(DEMO_USER_ID, skillId);
        String bearer = token();

        assertThat(json(get("/api/v1/me/tags", bearer)).get("tags").get(0)
                .get("used_in").asLong()).isEqualTo(1);

        jdbc.sql("UPDATE skill SET offline_at = :at WHERE id = :id")
                .param("at", AT).param("id", skillId).update();

        assertThat(json(get("/api/v1/me/tags", bearer)).get("tags").get(0)
                .get("used_in").asLong())
                .as("a dormant attachment is retained and not counted").isZero();
        assertThat(count("SELECT count(*) FROM my_tag_attachment"))
                .as("retained, not deleted").isEqualTo(1);
    }

    @Test
    void aPatchThatAlsoRefusesTheSentenceLeavesTheNameAlone() {
        String bearer = token();
        HttpResponse<String> created = postJson("/api/v1/me/tags", bearer, "{\"name\":\"客户项目\"}");
        String id = json(created).get("id").asText();

        // Both fields in one PATCH. The name is valid, the sentence is 201 code points: the answer
        // is a 400, and **nothing** may have been written — the old two-transaction shape committed
        // the rename before the second refusal.
        HttpResponse<String> refused = send(request("/api/v1/me/tags/" + id, bearer)
                .header("Content-Type", "application/json")
                .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"name\":\"新名字\",\"description\":\"" + "x".repeat(201) + "\"}"))
                .build());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(issue(refused)).isEqualTo("too_long");

        assertThat(jdbc.sql("SELECT name FROM my_tag_definition WHERE id = :id").param("id", id)
                .query(String.class).single())
                .as("the refused patch rolled the rename back with it").isEqualTo("客户项目");
        assertThat(auditRows("tag_definition_rename")).isZero();
    }

    @Test
    void aDefinitionIsResolvedByTheServersNormalization() {
        String bearer = token();
        postJson("/api/v1/me/tags", bearer, "{\"name\":\"PDF\"}");

        // The CLI addresses a definition by the word a person typed. Comparing display names on
        // the client would make `pdf` a different tag from `PDF`; the server is the one place
        // that decides they are the same.
        for (String typed : new String[] {"PDF", "pdf", "ＰＤＦ", "  pdf  "}) {
            JsonNode found = json(get("/api/v1/me/tags?name="
                    + java.net.URLEncoder.encode(typed, java.nio.charset.StandardCharsets.UTF_8),
                    bearer)).get("tags");
            assertThat(found).as("typed=%s", typed).hasSize(1);
            assertThat(found.get(0).get("name").asText()).isEqualTo("PDF");
        }

        assertThat(json(get("/api/v1/me/tags?name=no-such-tag", bearer)).get("tags")).isEmpty();
    }

    @Test
    void aNameCarryingTheDisplayHashIsRefused() {
        HttpResponse<String> refused = postJson("/api/v1/me/tags", token(), "{\"name\":\"#pdf\"}");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(issue(refused)).isEqualTo("invalid_name");
        assertThat(count("SELECT count(*) FROM my_tag_definition")).isZero();
    }

    @Test
    void theWebTagPageHasTwoTabsWithTwoCounts() {
        String skillId = publicSkill("pdf-tools");
        publicTag(skillId, "pdf", "PDF");
        definition(DEMO_USER_ID, "客户项目", "一句话");
        String bearer = token();
        assertThat(bearer).isNotBlank();

        JsonNode privateTab = json(webGet("/web/tags"));
        assertThat(privateTab.get("tags").get(0).get("name").asText()).isEqualTo("客户项目");
        assertThat(privateTab.get("tags").get(0).get("description").asText()).isEqualTo("一句话");

        JsonNode publicTab = json(webGet("/web/tags?tab=public"));
        assertThat(publicTab.get("tags").get(0).get("key").asText()).isEqualTo("pdf");
        assertThat(publicTab.get("tags").get(0).get("used_in").asLong()).isEqualTo(1);
    }
}
