package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The browser plane's v14 additions: discovery totals, the vocabularies and the information page's
 * join/leave block.
 */
@Sql("/sql/truncate-business-tables.sql")
class WebOrganizeIT extends AbstractOrganizeIT {

    @Test
    void theNewWebReadsRequireASession() {
        for (String path : new String[] {"/web/library", "/web/discover",
                "/web/discover/recommended", "/web/tags", "/web/categories",
                "/web/public-tags"}) {
            HttpResponse<String> response = send(request(path, null).GET().build());
            assertThat(response.statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    void discoveryCarriesThePageNumberAndTheFilteredTotal() {
        for (int i = 0; i < 8; i++) {
            publicSkill("skill-" + i);
        }
        token();

        JsonNode first = json(webGet("/web/discover?q=skill"));
        assertThat(first.get("p").asInt()).isEqualTo(1);
        assertThat(first.get("page_size").asInt()).isEqualTo(6);
        assertThat(first.get("total").asLong()).isEqualTo(8);
        assertThat(first.get("skills")).hasSize(6);

        JsonNode second = json(webGet("/web/discover?q=skill&p=2"));
        assertThat(second.get("p").asInt()).isEqualTo(2);
        assertThat(second.get("total").asLong()).isEqualTo(8);
        assertThat(second.get("skills")).hasSize(2);
    }

    @Test
    void theWebVocabulariesMatchTheApi() {
        String skill = publicSkill("pdf-tools");
        jdbc.sql("UPDATE skill SET category_key = 'doc' WHERE id = :id")
                .param("id", skill).update();
        publicTag(skill, "pdf", "PDF");
        token();

        JsonNode categories = json(webGet("/web/categories")).get("categories");
        assertThat(categories).hasSize(10);
        assertThat(categories.get(0).get("key").asText()).isEqualTo("doc");
        assertThat(categories.get(0).get("public_total").asLong()).isEqualTo(1);

        JsonNode tags = json(webGet("/web/public-tags?q=pdf")).get("tags");
        assertThat(tags).hasSize(1);
        assertThat(tags.get(0).get("key").asText()).isEqualTo("pdf");
        assertThat(tags.get(0).get("public_total").asLong()).isEqualTo(1);
    }

    @Test
    void theInformationPageKnowsWhichButtonToDraw() {
        publicSkill("mine");
        publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "joinable");
        String joined = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "joined");
        join(DEMO_USER_ID, joined);
        String shared = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "shared");
        grant(shared, DEMO_USER_ID, "browse");
        token();

        JsonNode mine = json(webGet("/web/skills/demo/mine/tags")).get("library");
        assertThat(mine.get("source").asText()).isEqualTo("created");
        assertThat(mine.get("joinable").asBoolean()).isFalse();
        assertThat(mine.get("can_remove").asBoolean()).isFalse();

        JsonNode joinable = json(webGet("/web/skills/other/joinable/tags")).get("library");
        assertThat(joinable.get("source").isNull()).isTrue();
        assertThat(joinable.get("active").asBoolean()).isFalse();
        assertThat(joinable.get("joinable").asBoolean()).isTrue();
        assertThat(joinable.get("can_remove").asBoolean()).isFalse();

        JsonNode joinedBlock = json(webGet("/web/skills/other/joined/tags")).get("library");
        assertThat(joinedBlock.get("source").asText()).isEqualTo("joined");
        assertThat(joinedBlock.get("active").asBoolean()).isTrue();
        assertThat(joinedBlock.get("joinable").asBoolean()).isFalse();
        assertThat(joinedBlock.get("can_remove").asBoolean()).isTrue();

        JsonNode sharedBlock = json(webGet("/web/skills/other/shared/tags")).get("library");
        assertThat(sharedBlock.get("source").asText()).isEqualTo("shared");
        assertThat(sharedBlock.get("can_remove").asBoolean())
                .as("a shared entry has no remove affordance on the page").isFalse();
    }

    @Test
    void thePrivateTagRowCarriesTheHiddenCount() {
        String skill = publicSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "pdf-tools");
        String definition = definition(DEMO_USER_ID, "客户项目", null);
        attach(definition, skill);
        join(DEMO_USER_ID, skill);
        token();

        JsonNode visible = json(webGet("/web/tags")).get("tags").get(0);
        assertThat(visible.get("used_in").asLong()).isEqualTo(1);
        assertThat(visible.get("dormant_in").asLong()).isZero();

        jdbc.sql("UPDATE skill SET offline_at = :at WHERE id = :id")
                .param("at", AT).param("id", skill).update();

        JsonNode dormant = json(webGet("/web/tags")).get("tags").get(0);
        assertThat(dormant.get("used_in").asLong()).isZero();
        assertThat(dormant.get("dormant_in").asLong())
                .as("the delete dialog's M").isEqualTo(1);
    }
}
