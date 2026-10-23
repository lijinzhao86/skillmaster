package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * Public tags and categories: who may write, what the vocabulary counts, and what recall they add
 * (test-plan T15–T17, T20–T22, T25, T27).
 */
@Sql("/sql/truncate-business-tables.sql")
class PublicTagCategoryApiIT extends AbstractOrganizeIT {

    @Test
    void onlyTheOwnerOrAnAdminMayWritePublicTagsAndCategories() {
        String skillId = publicSkill("pdf-tools");
        String demoToken = token();
        String otherId = registerAndSignIn(randomUsername(), randomPhone());

        // No grant: visible because public, but not the author.
        HttpResponse<String> strangerTag = postJson(
                "/api/v1/skills/demo/pdf-tools/tags/public", tokenFor(otherId), "{\"name\":\"x\"}");
        assertThat(strangerTag.statusCode()).isEqualTo(403);

        grant(skillId, otherId, "admin");
        HttpResponse<String> adminTag = postJson(
                "/api/v1/skills/demo/pdf-tools/tags/public", tokenFor(otherId), "{\"name\":\"x\"}");
        assertThat(adminTag.statusCode()).as(adminTag.body()).isEqualTo(200);

        grant(skillId, otherId, "maintain");
        HttpResponse<String> maintainTag = postJson(
                "/api/v1/skills/demo/pdf-tools/tags/public", tokenFor(otherId), "{\"name\":\"y\"}");
        assertThat(maintainTag.statusCode())
                .as("maintain is below admin and cannot organize").isEqualTo(403);

        assertThat(demoToken).isNotBlank();
    }

    @Test
    void publicTagCeilingNormalizationAndCollision() {
        String skillId = publicSkill("pdf-tools");
        for (int i = 0; i < 9; i++) {
            publicTag(skillId, "tag-" + i, "tag-" + i);
        }
        String bearer = token();

        HttpResponse<String> tenth = postJson("/api/v1/skills/demo/pdf-tools/tags/public",
                bearer, "{\"name\":\"PDF\"}");
        assertThat(tenth.statusCode()).as(tenth.body()).isEqualTo(200);
        assertThat(changed(tenth)).isTrue();

        HttpResponse<String> normalizedRepeat = postJson(
                "/api/v1/skills/demo/pdf-tools/tags/public", bearer, "{\"name\":\" pdf \"}");
        assertThat(normalizedRepeat.statusCode()).isEqualTo(200);
        assertThat(changed(normalizedRepeat)).isFalse();
        assertThat(count("SELECT count(*) FROM skill_public_tag WHERE skill_id = :id",
                java.util.Map.of("id", skillId))).isEqualTo(10);

        HttpResponse<String> eleventh = postJson(
                "/api/v1/skills/demo/pdf-tools/tags/public", bearer, "{\"name\":\"one-more\"}");
        assertThat(eleventh.statusCode()).isEqualTo(400);
        assertThat(issue(eleventh)).isEqualTo("tag_limit_reached");

        HttpResponse<String> shelf = postJson(
                "/api/v1/skills/demo/pdf-tools/tags/public", bearer,
                "{\"name\":\"文档与演示\"}");
        assertThat(shelf.statusCode()).isEqualTo(400);
        assertThat(issue(shelf)).isEqualTo("category_name_taken");

        assertThat(delete("/api/v1/skills/demo/pdf-tools/tags/public?name=not-there", bearer)
                .statusCode()).isEqualTo(404);
    }

    @Test
    void settingACategoryReplacesAndClearingIsANoOpWhenThereIsNone() {
        publicSkill("pdf-tools");
        String bearer = token();

        HttpResponse<String> first =
                putJson("/api/v1/skills/demo/pdf-tools/category", bearer, "{\"key\":\"doc\"}");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(changed(first)).isTrue();

        HttpResponse<String> same =
                putJson("/api/v1/skills/demo/pdf-tools/category", bearer, "{\"key\":\"doc\"}");
        assertThat(same.statusCode()).isEqualTo(200);
        assertThat(changed(same)).isFalse();

        HttpResponse<String> replaced =
                putJson("/api/v1/skills/demo/pdf-tools/category", bearer, "{\"key\":\"data\"}");
        assertThat(replaced.statusCode()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM skill WHERE name = 'pdf-tools'"
                + " AND category_key = 'data'")).isEqualTo(1);

        HttpResponse<String> stopped =
                putJson("/api/v1/skills/demo/pdf-tools/category", bearer, "{\"key\":\"ops\"}");
        assertThat(stopped.statusCode()).isEqualTo(400);
        assertThat(issue(stopped)).isEqualTo("category_stopped");

        // A word already assigned stays: selecting it again is a no-op even after it is stopped.
        jdbc.sql("UPDATE skill SET category_key = 'ops' WHERE name = 'pdf-tools'").update();
        HttpResponse<String> sameStopped =
                putJson("/api/v1/skills/demo/pdf-tools/category", bearer, "{\"key\":\"ops\"}");
        assertThat(sameStopped.statusCode()).isEqualTo(200);
        assertThat(changed(sameStopped)).isFalse();

        HttpResponse<String> cleared =
                delete("/api/v1/skills/demo/pdf-tools/category", bearer);
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        assertThat(changed(cleared)).isTrue();

        HttpResponse<String> clearedAgain =
                delete("/api/v1/skills/demo/pdf-tools/category", bearer);
        assertThat(clearedAgain.statusCode()).isEqualTo(200);
        assertThat(changed(clearedAgain)).isFalse();

        HttpResponse<String> unknown =
                putJson("/api/v1/skills/demo/pdf-tools/category", bearer, "{\"key\":\"nope\"}");
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(issue(unknown)).isEqualTo("unknown_category");
    }

    @Test
    void aRetiredWordIsRefused() {
        jdbc.sql("""
                INSERT INTO skill_category (key, name, state, sort_order, created_at)
                VALUES ('zz-retired', '已退役', 'retired', 99, :at)
                """).param("at", AT).update();
        publicSkill("pdf-tools");
        String bearer = token();

        HttpResponse<String> retired = putJson("/api/v1/skills/demo/pdf-tools/category", bearer,
                "{\"key\":\"zz-retired\"}");
        assertThat(retired.statusCode()).isEqualTo(400);
        assertThat(issue(retired)).isEqualTo("category_retired");

        jdbc.sql("DELETE FROM skill_category WHERE key = 'zz-retired'").update();
    }

    @Test
    void filingAndTaggingDoNotMoveTheVersionPointer() {
        String skillId = publicSkill("pdf-tools");
        String before = jdbc.sql("SELECT current_version_id FROM skill WHERE id = :id")
                .param("id", skillId).query(String.class).single();
        String bearer = token();

        putJson("/api/v1/skills/demo/pdf-tools/category", bearer, "{\"key\":\"doc\"}");
        postJson("/api/v1/skills/demo/pdf-tools/tags/public", bearer, "{\"name\":\"pdf\"}");

        String after = jdbc.sql("SELECT current_version_id FROM skill WHERE id = :id")
                .param("id", skillId).query(String.class).single();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void theVocabulariesCountOnlyDiscoverableSkills() {
        String publicSkill = publicSkill("public-one");
        String privateSkill = privateSkill("private-one");
        jdbc.sql("UPDATE skill SET category_key = 'doc' WHERE id IN (:a, :b)")
                .param("a", publicSkill).param("b", privateSkill).update();
        publicTag(publicSkill, "pdf", "PDF");
        publicTag(privateSkill, "pdf", "pdf");
        // A word that exists **only** on a private skill must not enter the public vocabulary at
        // all: the word form is the private author's, and listing it at count 0 would leak it.
        publicTag(privateSkill, "secret", "Secret");
        // The cross-skill spelling is the earliest attachment's; make the private one later so the
        // assertion is about the rule rather than about ULID order.
        jdbc.sql("UPDATE skill_public_tag SET created_at = '2026-10-22T00:00:00Z'"
                + " WHERE skill_id = :id").param("id", privateSkill).update();
        String bearer = token();

        JsonNode categories = json(get("/api/v1/categories", bearer)).get("categories");
        assertThat(categories).hasSize(10);
        assertThat(categories.get(0).get("key").asText()).isEqualTo("doc");
        assertThat(categories.get(0).get("public_total").asLong()).isEqualTo(1);
        assertThat(categories.valueStream()
                .filter(row -> "ops".equals(row.get("key").asText())).findFirst().orElseThrow()
                .get("state").asText()).isEqualTo("stopped");

        JsonNode tags = json(get("/api/v1/public-tags", bearer)).get("tags");
        assertThat(tags).hasSize(1);
        assertThat(tags.get(0).get("key").asText()).isEqualTo("pdf");
        assertThat(tags.get(0).get("public_total").asLong()).isEqualTo(1);
        assertThat(tags.get(0).get("name").asText())
                .as("the earliest attachment's spelling").isEqualTo("PDF");
    }

    @Test
    void publicTagsAndCategoriesParticipateInPublicRecall() {
        String publicSkill = publicSkill("pdf-tools");
        jdbc.sql("UPDATE skill SET category_key = 'doc' WHERE id = :id")
                .param("id", publicSkill).update();
        publicTag(publicSkill, "pdf", "PDF");
        publicSkill("other");
        String hidden = privateSkill("hidden");
        publicTag(hidden, "pdf", "PDF");
        String bearer = token();

        assertThat(names(skills(get("/api/v1/skills?scope=public&q=PDF", bearer))))
                .contains("pdf-tools");
        assertThat(names(skills(get(
                "/api/v1/skills?scope=public&category=文档与演示", bearer))))
                .containsExactly("pdf-tools");
        assertThat(names(skills(get(
                "/api/v1/skills?scope=public&public_tag=PDF", bearer))))
                .containsExactly("pdf-tools");
        assertThat(names(skills(get("/api/v1/skills?scope=public&q=hidden", bearer))))
                .as("a private skill never enters the public plane, tag or no tag")
                .isEmpty();
    }

    @Test
    void thePublicTagVocabularyPagesAndFilters() {
        String one = publicSkill("one");
        String two = publicSkill("two");
        publicTag(one, "alpha", "Alpha");
        publicTag(one, "alpha", "Alpha");
        publicTag(two, "beta", "Beta");
        String bearer = token();

        JsonNode page = json(get("/api/v1/public-tags?limit=1", bearer));
        assertThat(page.get("tags")).hasSize(1);
        assertThat(page.get("next_cursor").isNull()).isFalse();
        JsonNode second = json(get("/api/v1/public-tags?limit=1&cursor="
                + page.get("next_cursor").asText(), bearer));
        assertThat(second.get("tags")).hasSize(1);
        assertThat(names(second.get("tags")))
                .as("count descending, then name").containsExactly("Beta");

        JsonNode filtered = json(get("/api/v1/public-tags?q=alp", bearer)).get("tags");
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("key").asText()).isEqualTo("alpha");
    }
}
