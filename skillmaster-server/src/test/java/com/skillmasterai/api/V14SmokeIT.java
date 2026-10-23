package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * One call through every v14 endpoint, so a SQL mistake fails here rather than inside an assertion
 * about a product rule. The numbered tests beside this one assert the rules.
 */
@Sql("/sql/truncate-business-tables.sql")
class V14SmokeIT extends AbstractOrganizeIT {

    @Test
    void everyNewRouteAnswers() {
        String skillId = publicSkill("pdf-tools");
        publicTag(skillId, "pdf", "PDF");
        String def = definition(DEMO_USER_ID, "客户项目", "交付期内使用");
        attach(def, skillId);

        String token = token();

        HttpResponse<String> library = get("/api/v1/skills?scope=library&show=all", token);
        assertThat(library.statusCode()).as(library.body()).isEqualTo(200);
        assertThat(names(skills(library))).contains("pdf-tools");

        HttpResponse<String> tags =
                get("/api/v1/skills/demo/pdf-tools/tags", token);
        assertThat(tags.statusCode()).as(tags.body()).isEqualTo(200);
        assertThat(json(tags).get("my_tags").get("readable").asBoolean()).isTrue();

        HttpResponse<String> categories = get("/api/v1/categories", token);
        assertThat(categories.statusCode()).as(categories.body()).isEqualTo(200);
        assertThat(json(categories).get("categories").size()).isEqualTo(10);

        HttpResponse<String> publicTags = get("/api/v1/public-tags?q=pdf", token);
        assertThat(publicTags.statusCode()).as(publicTags.body()).isEqualTo(200);
        assertThat(json(publicTags).get("tags").get(0).get("public_total").asLong()).isEqualTo(1);

        HttpResponse<String> myTags = get("/api/v1/me/tags", token);
        assertThat(myTags.statusCode()).as(myTags.body()).isEqualTo(200);
        assertThat(json(myTags).get("tags").get(0).get("used_in").asLong()).isEqualTo(1);

        HttpResponse<String> setCategory = send(request("/api/v1/skills/demo/pdf-tools/category", token)
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString("{\"key\":\"doc\"}"))
                .build());
        assertThat(setCategory.statusCode()).as(setCategory.body()).isEqualTo(200);
        assertThat(json(setCategory).get("changed").asBoolean()).isTrue();

        HttpResponse<String> addPublic = send(
                request("/api/v1/skills/demo/pdf-tools/tags/public", token)
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                "{\"name\":\"批量处理\"}"))
                        .build());
        assertThat(addPublic.statusCode()).as(addPublic.body()).isEqualTo(200);

        HttpResponse<String> addMy = send(
                request("/api/v1/skills/demo/pdf-tools/tags/my", token)
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                "{\"name\":\"这周要用\"}"))
                        .build());
        assertThat(addMy.statusCode()).as(addMy.body()).isEqualTo(200);

        // Web plane: the same rows, a session and a CSRF token.
        HttpResponse<String> webLibrary = webGet("/web/library?show=all");
        assertThat(webLibrary.statusCode()).as(webLibrary.body()).isEqualTo(200);
        assertThat(json(webLibrary).get("total_entries").asLong()).isEqualTo(1);

        HttpResponse<String> webTags = webGet("/web/tags");
        assertThat(webTags.statusCode()).as(webTags.body()).isEqualTo(200);

        HttpResponse<String> webPublicTags = webGet("/web/tags?tab=public");
        assertThat(webPublicTags.statusCode()).as(webPublicTags.body()).isEqualTo(200);

        HttpResponse<String> recommended = webGet("/web/discover/recommended");
        assertThat(recommended.statusCode()).as(recommended.body()).isEqualTo(200);

        HttpResponse<String> discover = webGet("/web/discover?q=pdf");
        assertThat(discover.statusCode()).as(discover.body()).isEqualTo(200);

        HttpResponse<String> createDefinition = webPost("/web/tags",
                json(Map.of("name", "新标签")), csrfToken());
        assertThat(createDefinition.statusCode()).as(createDefinition.body()).isEqualTo(201);
    }
}
