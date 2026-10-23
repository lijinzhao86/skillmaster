package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Ulid;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The bounded "recommended for you" rule (v14, PRD #8 / test-plan T37).
 *
 * <p>The rule is one sentence — public, published, online, not in my library, newest first — and
 * the case that reads oddly to a person is the one pinned first: <strong>your own published skill
 * is not recommended to you</strong>, because "my library" includes what I created. Publishing it
 * does not move it out; only another account sees it in their stream.
 */
@Sql("/sql/truncate-business-tables.sql")
class DiscoverRecommendedIT extends AbstractOrganizeIT {

    @Test
    void myOwnPublicSkillIsNotRecommendedToMe() {
        publicSkill("coding");
        token();

        assertThat(json(webGet("/web/discover/recommended")).get("skills"))
                .as("it is mine, so it is already in my library — the stream is for what is not")
                .isEmpty();
    }

    @Test
    void onlyPublicPublishedOnlineSkillsOutsideMyLibraryAreRecommended() {
        publicSkill("mine");
        publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "other-public");

        privateSkill("private");
        draftSkill("draft");
        offlineSkill("offline");
        skill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "deleted-public", "public", Ulid.generate(),
                null, AT, null);

        String joined = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "joined");
        join(DEMO_USER_ID, joined);
        String shared = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "shared");
        grant(shared, DEMO_USER_ID, "browse");

        token();

        assertThat(names(json(webGet("/web/discover/recommended")).get("skills")))
                .containsExactly("other-public");
    }

    @Test
    void theBatchIsCappedWhateverTheQueryAsksFor() {
        for (int i = 0; i < 55; i++) {
            publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "bulk-" + i);
        }
        token();

        JsonNode page = json(webGet("/web/discover/recommended?limit=1000"));
        assertThat(page.get("skills")).as("the product's cap, not the caller's number").hasSize(50);
        assertThat(page.get("next_cursor").isNull()).isFalse();
    }

    @Test
    void batchesAreSixNewestFirstAndTheCursorOnlyAdvances() {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = "other-" + i;
            String id = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, name);
            jdbc.sql("""
                    UPDATE skill SET current_version_submitted_at = :at WHERE id = :id
                    """).param("at", "2026-10-20T09:12:0" + i + "Z").param("id", id).update();
            created.add(name);
        }
        token();

        JsonNode first = json(webGet("/web/discover/recommended"));
        assertThat(names(first.get("skills")))
                .containsExactly("other-7", "other-6", "other-5", "other-4", "other-3", "other-2");
        String cursor = first.get("next_cursor").asText();
        assertThat(cursor).isNotBlank();

        JsonNode second = json(webGet("/web/discover/recommended?cursor="
                + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(names(second.get("skills"))).containsExactly("other-1", "other-0");
        assertThat(second.get("next_cursor").isNull()).isTrue();

        HttpResponse<String> page = webGet("/web/discover/recommended");
        assertThat(page.statusCode()).isEqualTo(200);
    }
}
