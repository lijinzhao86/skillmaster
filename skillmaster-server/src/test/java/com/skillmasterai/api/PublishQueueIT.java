package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Browser;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /web/publish-queue} (v18): what is waiting, for whom, in which order.
 *
 * <p><strong>The queue is composed, not joined.</strong> Which skills are on it is M12's question and
 * which drafts they hold is M7's, so the use case reads one then the other. These tests therefore
 * care about the composition's results: a skill with nothing waiting is absent, a skill this caller
 * cannot publish from is absent, and the order is the server's own so that the page does not have to
 * invent one.
 */
@Sql("/sql/truncate-business-tables.sql")
class PublishQueueIT extends AbstractAccountIT {

    private static final String QUEUE = "/web/publish-queue";

    private String username;
    private String phone;
    private String userId;
    private String token;

    @BeforeEach
    void signUp() {
        username = randomUsername();
        phone = randomPhone();
        userId = registerAndSignIn(username, phone);
        token = tokenFor(userId);
    }

    @Test
    void listsTheSkillsWithWaitingDraftsNewestFirstAndSaysWhatIsLive() {
        submit("never-published", "初版", null);

        String live = submit("published-with-a-draft", "第一份", null);
        publish("published-with-a-draft", live, "1.0.0");
        submit("published-with-a-draft", "第二份", "minor");

        String drained = submit("no-draft-left", "将要上线", null);
        publish("no-draft-left", drained, "1.0.0");

        // **时间写死，不靠跑得多快**：两条草稿在同一秒提交，先后就成了 id 的事——而这一条问的正是
        // "新的在前"。改的是投影列与版本行两处，因为它们各回答一个问题：skills 之间按投影排，
        // skill 之内按版本行排。
        stamp("never-published", "初版", "2026-10-01T09:00:00Z");
        stamp("published-with-a-draft", "第二份", "2026-10-02T09:00:00Z");

        JsonNode body = queue();
        JsonNode skills = body.get("skills");

        assertThat(body.get("truncated").asBoolean()).isFalse();
        assertThat(names(skills)).containsExactly("published-with-a-draft", "never-published");

        JsonNode first = skills.get(0);
        assertThat(first.get("current").get("version").asText()).isEqualTo("1.0.0");
        assertThat(draftNames(first)).containsExactly("第二份");
        assertThat(first.get("drafts").get(0).get("release_type").asText()).isEqualTo("minor");
        // **code 原样就是 pin**：不加 `draft:`，也不加任何前缀（ADR 0063）。
        assertThat(first.get("drafts").get(0).get("code").asText()).hasSize(8);

        assertThat(skills.get(1).get("current").isNull())
                .as("a skill that has never been published carries no current version at all")
                .isTrue();
        assertThat(skills.get(1).get("drafts").get(0).get("release_type").isNull())
                .as("and a draft without a type says null rather than an empty string")
                .isTrue();
    }

    @Test
    void theQueueIsOnlyWhatThisCallerMayPublishFrom() {
        // Somebody else's skill, shared down to this account: first at `write`, then at `maintain`.
        String ownerPhone = randomPhone();
        String owner = randomUsername();
        String ownerToken = tokenFor(registerAndSignIn(owner, ownerPhone));
        // The shared jar is the owner's now; sign this test's account back in with its own browser.
        Browser mine = secondSignedInBrowser(phone, PASSWORD);

        String draft = submitAs(ownerToken, owner, "shared-skill", "第一份", null);
        assertThat(shareAs(ownerToken, owner, username, "write").statusCode()).isEqualTo(201);

        assertThat(JSON.readTree(read(mine).body()).get("skills"))
                .as("`write` may add a draft and cannot publish one, so the queue does not list it")
                .isEmpty();

        assertThat(shareAs(ownerToken, owner, username, "maintain").statusCode()).isEqualTo(201);

        JsonNode skills = JSON.readTree(read(mine).body()).get("skills");
        assertThat(names(skills)).as("`maintain` is enough to publish from").containsExactly("shared-skill");
        assertThat(skills.get(0).get("namespace").asText()).isEqualTo(owner);
        assertThat(skills.get(0).get("drafts").get(0).get("code").asText()).isEqualTo(draft);
    }

    @Test
    void moreThanFiveHundredSkillsIsSaidOutLoud() {
        String namespaceId = jdbc.sql("SELECT id FROM namespace WHERE slug = :slug")
                .param("slug", username).query(String.class).single();
        String at = Timestamps.now();

        // **真的铺满 501 行**：只按 500 去取的话，"正好 500"与"被截断"都是 500 行，页面分不出来
        // (PRD 验收 19)。直接插入而不是走提交，是因为这一条问的是读取的边界，不是写入的速度。
        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, description, frontmatter, visibility,
                                   current_version_id, created_by, created_at, updated_at,
                                   draft_count, newest_draft_submitted_at)
                SELECT 'bulk-skill-' || i, :namespace, 'bulk-' || i, 'd', '{}', 'private',
                       NULL, :user, :at, :at, 1, :at
                FROM generate_series(1, 501) AS i
                """)
                .param("namespace", namespaceId).param("user", userId).param("at", at)
                .update();
        jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, version, digest, file_count,
                                           total_bytes, changelog, source, submitted_by,
                                           submitted_at, state, state_at, first_published_at,
                                           title, description, frontmatter, draft_name)
                SELECT 'bulk-version-' || i, 'bulk-skill-' || i, 1, NULL, 'sha256:' || md5(i::text),
                       0, 0, '', 'zip', :user, :at, 'draft', NULL, NULL, '', 'd', '{}',
                       '批量 ' || i
                FROM generate_series(1, 501) AS i
                """)
                .param("user", userId).param("at", at)
                .update();

        JsonNode body = queue();
        assertThat(body.get("truncated").asBoolean()).isTrue();
        assertThat(body.get("skills")).as("the five-hundred-and-first is what says 「cut at 500」")
                .hasSize(500);
        assertThat(body.get("skills").get(0).get("drafts").get(0).get("name").asText())
                .as("the skills that are listed still carry their drafts")
                .startsWith("批量 ");
    }

    @Test
    void aDraftThatCarriesItsOwnNumberIsNotOnTheQueueAndNeitherIsADeletedSkill() {
        // **A draft with its own number is not the queue's subject.** The page answers "what number
        // will this become", and this row's publish ignores the answer (TD §算号, first branch), so
        // showing it would preview one number and publish another. The TD says the queue does not
        // carry it; this is where that is true.
        String legacy = submit("legacy-row", "旧草稿", "patch");
        jdbc.sql("UPDATE skill_version SET version = '0.3.1' WHERE id = :id")
                .param("id", legacy).update();

        // And a deleted skill keeps its drafts but has nothing to publish to.
        submit("deleted-row", "一份", null);
        jdbc.sql("UPDATE skill SET deleted_at = :at WHERE name = 'deleted-row'")
                .param("at", Timestamps.now()).update();

        assertThat(names(queue().get("skills")))
                .as("neither row belongs on a page about what to publish next")
                .isEmpty();
    }

    @Test
    void theAuthorPlaneAnswersTheSameQuestionForTheCli() {
        // ADR 0081: every console read has a CLI counterpart, and the CLI has no session — so the
        // same use case is reachable with a key. One shape, one spelling.
        submit("from-the-cli", "第一份", "minor");

        HttpResponse<String> response = get("/api/v1/author/publish-queue", token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(names(body.get("skills"))).containsExactly("from-the-cli");
        assertThat(body.get("skills").get(0).get("drafts").get(0).get("release_type").asText())
                .isEqualTo("minor");
        assertThat(body.get("truncated").asBoolean()).isFalse();

        // A key is the same person, so it gets the same rows the browser just got.
        assertThat(body.get("skills")).isEqualTo(queue().get("skills"));
    }

    @Test
    void theQueueNeedsASession() {
        Browser stranger = new Browser();
        HttpResponse<String> response = stranger.get(uri(QUEUE));
        assertThat(response.statusCode())
                .as("`/web/**` is permitAll unless a path is listed, so this route has to be named")
                .isEqualTo(401);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private JsonNode queue() {
        HttpResponse<String> response = webGet(QUEUE);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> read(Browser browser) {
        return browser.get(uri(QUEUE));
    }

    private List<String> names(JsonNode skills) {
        List<String> out = new ArrayList<>();
        skills.forEach(skill -> out.add(skill.get("name").asText()));
        return out;
    }

    private List<String> draftNames(JsonNode skill) {
        List<String> out = new ArrayList<>();
        skill.get("drafts").forEach(draft -> out.add(draft.get("name").asText()));
        return out;
    }

    /**
     * Writes one draft's submission time, in both places it is read from.
     *
     * <p>The version row is what the draft itself carries; the projection on {@code skill} is what
     * the queue orders skills by. A fixture that moved one and not the other would answer the two
     * questions with two different orders — which is exactly the bug the copy exists to prevent.
     */
    private void stamp(String skillName, String draftName, String at) {
        jdbc.sql("""
                UPDATE skill_version SET submitted_at = :at
                WHERE draft_name = :draft AND skill_id = (SELECT id FROM skill WHERE name = :name)
                """)
                .param("at", at).param("draft", draftName).param("name", skillName).update();
        jdbc.sql("UPDATE skill SET newest_draft_submitted_at = :at WHERE name = :name")
                .param("at", at).param("name", skillName).update();
    }

    private String submit(String skillName, String versionName, String releaseType) {
        return submitAs(token, username, skillName, versionName, releaseType);
    }

    private String submitAs(String asToken, String owner, String skillName, String versionName,
            String releaseType) {
        Multipart multipart = Multipart.create()
                .file("file", skillName + ".zip", Zips.ofText(Map.of(
                        "SKILL.md", "---\nname: " + skillName + "\ndescription: d\n---\n",
                        "references/" + versionName + ".md", versionName + " in " + skillName)))
                .field("name", versionName);
        if (releaseType != null) {
            multipart.field("release_type", releaseType);
        }
        HttpResponse<String> response = send(request("/api/v1/skills", asToken)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
        assertThat(response.statusCode()).as("submit failed: %s", response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("version").get("draft_id").asText();
    }

    private void publish(String skillName, String draftCode, String releaseName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", draftCode);
        body.put("release_name", releaseName);
        HttpResponse<String> response =
                webPost("/web/skills/" + username + "/" + skillName + "/publish", json(body));
        assertThat(response.statusCode()).as("publish failed: %s", response.body()).isEqualTo(200);
    }

    private HttpResponse<String> shareAs(String ownerToken, String owner, String handle, String role) {
        return send(request("/api/v1/skills/" + owner + "/shared-skill/grants", ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("handle", handle, "role", role))))
                .build());
    }
}
