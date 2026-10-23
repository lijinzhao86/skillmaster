package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The library's read and write rules (test-plan T4–T6, T8, T9, T11, T19).
 */
@Sql("/sql/truncate-business-tables.sql")
class LibraryApiIT extends AbstractOrganizeIT {

    @Test
    void theLibraryIsThreeKindsOfEntryAndOneDisplayedSource() {
        publicSkill("mine");
        String shared = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "shared");
        grant(shared, DEMO_USER_ID, "browse");
        String joined = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "joined");
        join(DEMO_USER_ID, joined);
        publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "not-in-library");

        JsonNode cards = skills(get("/api/v1/skills?scope=library&show=all", token()));

        assertThat(names(cards)).containsExactlyInAnyOrder("mine", "shared", "joined");
        assertThat(cards.valueStream().collect(java.util.stream.Collectors.toMap(
                c -> c.get("name").asText(), c -> c.get("source").asText())))
                .containsEntry("mine", "created")
                .containsEntry("shared", "shared")
                .containsEntry("joined", "joined");
        assertThat(names(cards)).doesNotContain("not-in-library");
    }

    @Test
    void joinIsAReferenceWithTheDocumentedBoundary() {
        String mine = publicSkill("mine");
        String shared = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "shared");
        grant(shared, DEMO_USER_ID, "browse");
        String offline = skill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "offline", "public",
                UlidHolder.NEXT, AT, null, null);
        skill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "invisible", "private",
                UlidHolder.NEXT, null, null, null);
        String target = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "target");
        String bearer = token();

        assertThat(post("/api/v1/skills/demo/mine/library", bearer).statusCode()).isEqualTo(409);
        assertThat(json(post("/api/v1/skills/demo/mine/library", bearer)).get("error").get("code")
                .asText()).isEqualTo("skill_not_joinable");
        assertThat(post("/api/v1/skills/other/shared/library", bearer).statusCode())
                .isEqualTo(409);
        assertThat(post("/api/v1/skills/other/offline/library", bearer).statusCode()).isEqualTo(409);
        assertThat(post("/api/v1/skills/other/invisible/library", bearer).statusCode())
                .isEqualTo(404);

        HttpResponse<String> first = post("/api/v1/skills/other/target/library", bearer);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(changed(first)).isTrue();
        HttpResponse<String> again = post("/api/v1/skills/other/target/library", bearer);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(changed(again)).as("a repeat is a no-op, including a dormant one").isFalse();
        assertThat(count("SELECT count(*) FROM skill_library_join WHERE skill_id = :id",
                java.util.Map.of("id", target))).isEqualTo(1);

        // A join did not copy anything or change who may read it: the skill stays public and
        // readable, and the reference is the only row that appeared.
        assertThat(names(skills(get("/api/v1/skills?scope=library&show=all", bearer))))
                .contains("target");
        assertThat(count("SELECT count(*) FROM skill_version WHERE skill_id = :id",
                java.util.Map.of("id", target)))
                .as("joining creates no version").isZero();
    }

    @Test
    void leaveRemovesOnlyAReferenceAndTheTagsSleep() {
        String joined = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "joined");
        join(DEMO_USER_ID, joined);
        String definition = definition(DEMO_USER_ID, "客户项目", null);
        attach(definition, joined);
        String bearer = token();

        JsonNode before = json(get("/api/v1/skills/other/joined/tags", bearer));
        assertThat(before.get("my_tags").get("readable").asBoolean()).isTrue();

        HttpResponse<String> left = delete("/api/v1/skills/other/joined/library", bearer);
        assertThat(left.statusCode()).as(left.body()).isEqualTo(200);
        assertThat(changed(left)).isTrue();

        JsonNode after = json(get("/api/v1/skills/other/joined/tags", bearer));
        assertThat(after.get("my_tags").get("readable").asBoolean()).isFalse();
        assertThat(after.get("my_tags").get("reason").asText()).isEqualTo("not_in_library");
        assertThat(count("SELECT count(*) FROM my_tag_attachment WHERE definition_id = :id",
                java.util.Map.of("id", definition)))
                .as("the tag is retained and sleeps").isEqualTo(1);

        join(DEMO_USER_ID, joined);
        JsonNode rejoined = json(get("/api/v1/skills/other/joined/tags", bearer));
        assertThat(rejoined.get("my_tags").get("readable").asBoolean()).isTrue();
    }

    @Test
    void leaveWithoutAReferenceIsAConflictAndAGrantDoesNotHideOne() {
        publicSkill("mine");
        String joined = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "joined");
        join(DEMO_USER_ID, joined);
        grant(joined, DEMO_USER_ID, "browse");
        String bearer = token();

        HttpResponse<String> mine = delete("/api/v1/skills/demo/mine/library", bearer);
        assertThat(mine.statusCode()).isEqualTo(409);
        assertThat(json(mine).get("error").get("code").asText())
                .isEqualTo("library_reference_missing");

        // The displayed source is "shared", but the reference is still there and leaving removes it.
        HttpResponse<String> shared = delete("/api/v1/skills/other/joined/library", bearer);
        assertThat(shared.statusCode()).as(shared.body()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM skill_library_join WHERE skill_id = :id",
                java.util.Map.of("id", joined))).isZero();

        HttpResponse<String> again = delete("/api/v1/skills/other/joined/library", bearer);
        assertThat(again.statusCode()).isEqualTo(409);
    }

    @Test
    void aJoinRowMakesASecondJoinANoOpEvenAfterAGrantRaisesTheDisplayedSource() {
        String joined = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "joined");
        join(DEMO_USER_ID, joined);
        // The grant raises the displayed source to "shared"; the join row is still the caller's.
        grant(joined, DEMO_USER_ID, "browse");
        String bearer = token();

        JsonNode card = skills(get("/api/v1/skills?scope=library&show=all", bearer)).get(0);
        assertThat(card.get("source").asText()).isEqualTo("shared");

        HttpResponse<String> again = post("/api/v1/skills/other/joined/library", bearer);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(changed(again))
                .as("the reference is already there — joining adds nothing, and is not a 409")
                .isFalse();
    }

    @Test
    void aReferenceToASoftDeletedSkillCanStillBeRemoved() {
        String joined = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "joined");
        join(DEMO_USER_ID, joined);
        jdbc.sql("UPDATE skill SET deleted_at = :at WHERE id = :id")
                .param("at", AT).param("id", joined).update();
        String bearer = token();

        HttpResponse<String> left = delete("/api/v1/skills/other/joined/library", bearer);
        assertThat(left.statusCode()).as(left.body()).isEqualTo(200);
        assertThat(changed(left)).isTrue();
        assertThat(count("SELECT count(*) FROM skill_library_join WHERE skill_id = :id",
                java.util.Map.of("id", joined))).isZero();
    }

    @Test
    void theApiDeletedListRefusesTagFiltersInsteadOfIgnoringThem() {
        deletedSkill("gone");
        definition(DEMO_USER_ID, "客户项目", null);
        String bearer = token();

        HttpResponse<String> refused = get(
                "/api/v1/skills?scope=library&show=deleted&my_tag=客户项目", bearer);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);

        HttpResponse<String> allowed =
                get("/api/v1/skills?scope=library&show=deleted&source=created", bearer);
        assertThat(allowed.statusCode()).as(allowed.body()).isEqualTo(200);
        assertThat(names(skills(allowed))).containsExactly("gone");
    }

    @Test
    void aRetiredCategoryNameSelectsNothing() {
        String id = publicSkill("pdf-tools");
        jdbc.sql("UPDATE skill SET category_key = 'doc' WHERE id = :id").param("id", id).update();
        String name = jdbc.sql("SELECT name FROM skill_category WHERE key = 'doc'")
                .query(String.class).single();
        String encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
        String bearer = token();

        assertThat(json(get("/api/v1/skills?scope=public&category=" + encoded, bearer))
                .get("skills")).as("the shelf still filters while it is selectable").hasSize(1);

        // `skill_category` is platform seed data and deliberately **not** truncated between tests;
        // a state written here would leak into every later class, so it is put back.
        jdbc.sql("UPDATE skill_category SET state = 'retired' WHERE key = 'doc'").update();
        try {
            assertThat(json(get("/api/v1/skills?scope=public&category=" + encoded, bearer))
                    .get("skills"))
                    .as("a retired word is not a filter, even if a stale row carries it").isEmpty();
        } finally {
            jdbc.sql("UPDATE skill_category SET state = 'enabled' WHERE key = 'doc'").update();
        }
    }

    @Test
    void thePublicScopeRequiresAnInputAndRefusesLibraryParameters() {
        publicSkill("pdf-tools");
        String bearer = token();

        assertThat(get("/api/v1/skills?scope=public", bearer).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/skills?scope=public&q=pdf&sort=recent", bearer).statusCode())
                .isEqualTo(400);
        assertThat(get("/api/v1/skills?scope=public&q=pdf&show=live", bearer).statusCode())
                .isEqualTo(400);
        assertThat(get("/api/v1/skills?scope=public&q=pdf&my_tag=x", bearer).statusCode())
                .isEqualTo(400);
        assertThat(get("/api/v1/skills?scope=public&q=pdf&namespace=mine", bearer).statusCode())
                .as("the public plane has no library namespaces; ignoring the filter would lie")
                .isEqualTo(400);
        assertThat(get("/api/v1/skills?scope=public&q=pdf", bearer).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/skills?scope=nonsense&q=pdf", bearer).statusCode()).isEqualTo(400);
    }

    @Test
    void thePublicScopeServesOnlyPublicPublishedOnlineLiveSkills() {
        publicSkill("live");
        privateSkill("private");
        offlineSkill("offline");
        draftSkill("draft");
        deletedSkill("deleted");
        String bearer = token();

        assertThat(names(skills(get("/api/v1/skills?scope=public&q=live", bearer))))
                .containsExactly("live");
        assertThat(names(skills(get("/api/v1/skills?scope=public&q=private", bearer)))).isEmpty();
        assertThat(names(skills(get("/api/v1/skills?scope=public&q=offline", bearer)))).isEmpty();
        assertThat(names(skills(get("/api/v1/skills?scope=public&q=draft", bearer)))).isEmpty();
        assertThat(names(skills(get("/api/v1/skills?scope=public&q=deleted", bearer)))).isEmpty();
    }

    @Test
    void theLibraryShowStatesAndSourceFilter() {
        publicSkill("mine");
        draftSkill("draft");
        offlineSkill("offline");
        deletedSkill("deleted");
        String bearer = token();

        assertThat(names(skills(get("/api/v1/skills?scope=library", bearer))))
                .contains("mine").doesNotContain("draft", "offline", "deleted");
        assertThat(names(skills(get("/api/v1/skills?scope=library&show=draft", bearer))))
                .containsExactly("draft");
        assertThat(names(skills(get("/api/v1/skills?scope=library&show=offline", bearer))))
                .containsExactly("offline");
        assertThat(names(skills(get("/api/v1/skills?scope=library&show=all", bearer))))
                .contains("mine", "draft", "offline").doesNotContain("deleted");
        assertThat(names(skills(get("/api/v1/skills?scope=library&show=deleted", bearer))))
                .containsExactly("deleted");
        assertThat(names(skills(get(
                "/api/v1/skills?scope=library&show=deleted&source=created", bearer))))
                .containsExactly("deleted");
        assertThat(names(skills(get(
                "/api/v1/skills?scope=library&show=deleted&source=joined", bearer))))
                .as("a source other than created selects nothing on the deleted list")
                .isEmpty();
    }

    @Test
    void categoryAnyOfAndTagsAllOfNarrowTheLibrary() {
        String one = publicSkill("one");
        String two = publicSkill("two");
        jdbc.sql("UPDATE skill SET category_key = 'doc' WHERE id = :id")
                .param("id", one).update();
        jdbc.sql("UPDATE skill SET category_key = 'data' WHERE id = :id")
                .param("id", two).update();
        publicTag(one, "pdf", "PDF");
        publicTag(two, "pdf", "PDF");
        publicTag(two, "表格", "表格");
        String def = definition(DEMO_USER_ID, "客户项目", null);
        attach(def, two);
        String bearer = token();

        assertThat(names(skills(get(
                "/api/v1/skills?scope=library&category=文档与演示&category=数据与分析", bearer))))
                .containsExactlyInAnyOrder("one", "two");
        assertThat(names(skills(get("/api/v1/skills?scope=library&category=文档与演示", bearer))))
                .containsExactly("one");
        assertThat(names(skills(get("/api/v1/skills?scope=library&public_tag=PDF", bearer))))
                .containsExactlyInAnyOrder("one", "two");
        assertThat(names(skills(get(
                "/api/v1/skills?scope=library&public_tag=PDF&public_tag=表格", bearer))))
                .as("two tags are an AND")
                .containsExactly("two");
        assertThat(names(skills(get("/api/v1/skills?scope=library&my_tag=客户项目", bearer))))
                .containsExactly("two");
        assertThat(names(skills(get("/api/v1/skills?scope=library&category=nope", bearer))))
                .as("an unknown name selects nothing rather than widening")
                .isEmpty();
    }

    @Test
    void restoreIsA409ForALiveSkillAndSucceedsForADeletedOne() {
        publicSkill("live");
        deletedSkill("gone");
        String bearer = token();

        HttpResponse<String> live = post("/api/v1/skills/demo/live/restore", bearer);
        assertThat(live.statusCode()).isEqualTo(409);
        assertThat(json(live).get("error").get("code").asText()).isEqualTo("skill_not_deleted");

        HttpResponse<String> restored = post("/api/v1/skills/demo/gone/restore", bearer);
        assertThat(restored.statusCode()).as(restored.body()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM skill WHERE name = 'gone' AND deleted_at IS NULL"))
                .isEqualTo(1);
    }

    @Test
    void anUnreachablePageNumberIsA400RatherThanANegativeOffset() {
        publicSkill("pdf-tools");
        token();

        // (p - 1) * 10 overflows int here; the answer used to be a PostgreSQL error and a 500.
        assertThat(webGet("/web/library?p=214748366").statusCode()).isEqualTo(400);
    }

    @Test
    void theWebLibrarySendsTheThreeCountersAndIgnoresTagFiltersWhenDeleted() {
        String live = publicSkill("live");
        deletedSkill("gone");
        String definition = definition(DEMO_USER_ID, "客户项目", null);
        attach(definition, live);
        token();

        JsonNode all = json(webGet("/web/library?show=all"));
        assertThat(all.get("total_entries").asLong()).isEqualTo(2);
        assertThat(all.get("effective_entries").asLong()).isEqualTo(1);
        assertThat(all.get("filtered_total").asLong()).isEqualTo(1);

        JsonNode filtered = json(webGet("/web/library?show=all&q=gone"));
        assertThat(filtered.get("filtered_total").asLong())
                .as("filtered_total follows the filters").isZero();
        assertThat(filtered.get("total_entries").asLong())
                .as("the other two counters never move with a filter").isEqualTo(2);
        assertThat(filtered.get("effective_entries").asLong()).isEqualTo(1);

        JsonNode deleted = json(webGet(
                "/web/library?show=deleted&my-tag=" + definition));
        assertThat(deleted.get("filtered_total").asLong())
                .as("tag filters are ignored on the deleted list").isEqualTo(1);
    }

    /** A fresh ULID for a row whose id the test does not need to name. */
    private static final class UlidHolder {
        static final String NEXT = com.skillmasterai.common.Ulid.generate();
    }
}
