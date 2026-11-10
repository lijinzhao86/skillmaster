package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * v58's collection edge: what the HTTP planes write, and how the same rows read back.
 *
 * <p>The repository tests cover the correlation algorithm; these go through the real controllers,
 * because the half that can be wrong without them is exactly the wiring between a controller and
 * {@link ActivityLog}: a seam that compiles and records nothing is silent, and the page it feeds
 * would simply look empty.
 */
@Sql("/sql/truncate-business-tables.sql")
class UsageCollectionIT extends AbstractAccountIT {

    private static final String DEMO_NAMESPACE_ID = "01M3HTGC79VYJGM8BFXHX2QYNH";

    private static final String DEMO_SKILL = "/api/v1/skills/demo/pdf-tools";
    private static final String WEB_SKILL = "/web/skills/demo/pdf-tools";
    private static final String INTERACTIONS = "/api/v1/usage/interactions";

    private static final String SKILL_MD = """
            ---
            name: pdf-tools
            description: 提取与合并 PDF
            ---
            # PDF 工具
            """;

    @Test
    void aSearchIsRecordedUnderTheQueryAndItsResults() {
        insertSkill();
        String token = token();

        assertThat(get("/api/v1/skills?q=pdf-tools", token).statusCode()).isEqualTo(200);

        Map<String, Object> event = jdbc.sql("""
                SELECT kind, target, detail FROM activity_event WHERE kind = 'search'
                """).query((rs, row) -> Map.<String, Object>of(
                "kind", rs.getString("kind"),
                "target", rs.getString("target"),
                "detail", rs.getString("detail")))
                .single();
        assertThat(event.get("target")).as("the query is the target").isEqualTo("pdf-tools");
        assertThat((String) event.get("detail"))
                .as("one response, one result, the flat id array in rank order")
                .contains("\"skill_ids\":[\"")
                .contains("\"returned\":1")
                .contains("\"pages\":1")
                // The API's search response carries no total (B6), so neither does the event.
                .contains("\"total\":null");

        JsonNode page = body(get(INTERACTIONS, token));
        assertThat(page.get("total").asLong()).isEqualTo(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("kind").asText()).isEqualTo("search");
        assertThat(item.get("query").asText()).isEqualTo("pdf-tools");
        assertThat(item.get("results").get(0).get("skill").asText()).isEqualTo("demo/pdf-tools");
        assertThat(item.get("results").get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(item.get("invocations")).as("nothing called yet").isEmpty();
    }

    @Test
    void aBodyReadAfterASearchIsAttributedToIt() {
        insertSkill();
        String token = token();

        get("/api/v1/skills?q=pdf-tools", token);
        assertThat(get(DEMO_SKILL + "/body", token).statusCode()).isEqualTo(200);

        Map<String, Object> invoke = jdbc.sql("""
                SELECT skill_address, version_id, version_label, search_event_id
                  FROM activity_event WHERE kind = 'invoke'
                """).query((rs, row) -> Map.<String, Object>of(
                "address", rs.getString("skill_address"),
                "version_id", rs.getString("version_id"),
                "label", rs.getString("version_label"),
                "search", rs.getObject("search_event_id")))
                .single();
        assertThat(invoke.get("address")).isEqualTo("demo/pdf-tools");
        assertThat(invoke.get("version_id")).isNotNull();
        assertThat(invoke.get("label")).isEqualTo("1.0.0");
        assertThat(invoke.get("search")).as("attributed to the search it followed").isNotNull();

        JsonNode page = body(get(INTERACTIONS, token));
        assertThat(page.get("total").asLong())
                .as("a call inside the window merges into the search, it is not a second row")
                .isEqualTo(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("kind").asText()).isEqualTo("search");
        JsonNode invocation = item.get("invocations").get(0);
        assertThat(invocation.get("skill").asText()).isEqualTo("demo/pdf-tools");
        assertThat(invocation.get("version").asText()).isEqualTo("1.0.0");
        assertThat(invocation.get("rank").asInt()).as("it was the first result").isEqualTo(1);
    }

    @Test
    void aBodyReadWithoutASearchIsADirectCall() {
        insertSkill();
        String token = token();

        assertThat(get(DEMO_SKILL + "/body", token).statusCode()).isEqualTo(200);

        JsonNode item = body(get(INTERACTIONS, token)).get("items").get(0);
        assertThat(item.get("kind").asText()).isEqualTo("direct");
        assertThat(item.get("skill").asText()).isEqualTo("demo/pdf-tools");
        assertThat(item.get("version").asText()).isEqualTo("1.0.0");
    }

    @Test
    void browsingWithoutAQueryIsNotASearch() {
        insertSkill();
        String token = token();

        assertThat(get("/api/v1/skills", token).statusCode()).isEqualTo(200);

        assertThat(count("SELECT count(*) FROM activity_event WHERE kind = 'browse'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM activity_event WHERE kind = 'search'"))
                .isZero();
        assertThat(body(get(INTERACTIONS, token)).get("total").asLong())
                .as("a browse is not an interaction").isZero();
    }

    @Test
    void theWebPreviewIsAFileReadNotAnInvoke() {
        insertSkill();
        // Signs this jar in as the seeded account, which owns the fixture.
        String token = token();
        assertThat(token).isNotBlank();

        assertThat(webGet(WEB_SKILL + "/body").statusCode()).isEqualTo(200);

        // `token()` signs in, which writes a `login` row of its own; this test is about the read.
        Map<String, Object> event = jdbc.sql("""
                SELECT kind, detail FROM activity_event WHERE kind = 'file_read'
                """).query((rs, row) -> Map.<String, Object>of(
                "kind", rs.getString("kind"),
                "detail", rs.getString("detail")))
                .single();
        assertThat(event.get("kind")).isEqualTo("file_read");
        assertThat((String) event.get("detail")).contains("SKILL.md");
        assertThat(count("SELECT count(*) FROM activity_event WHERE kind = 'invoke'")).isZero();
        assertThat(body(get(INTERACTIONS, token)).get("total").asLong())
                .as("a preview is not a call and does not appear in the history")
                .isZero();
    }

    @Test
    void aSearchWithNoResultsIsStillRecorded() {
        insertSkill();
        String token = token();

        assertThat(get("/api/v1/skills?q=nothing-matches-this", token).statusCode())
                .isEqualTo(200);

        var event = jdbc.sql("""
                SELECT target, detail FROM activity_event WHERE kind = 'search'
                """).query((rs, row) -> Map.<String, Object>of(
                "target", rs.getString("target"),
                "detail", rs.getString("detail")))
                .single();
        assertThat(event.get("target")).isEqualTo("nothing-matches-this");
        assertThat((String) event.get("detail")).contains("\"results\":[]");

        JsonNode item = body(get(INTERACTIONS, token)).get("items").get(0);
        assertThat(item.get("returned").asInt()).isZero();
        assertThat(item.get("results")).isEmpty();
        assertThat(item.get("invocations")).isEmpty();
    }

    @Test
    void theWebUsagePlaneRefusesAnAnonymousRequest() {
        insertSkill();

        // Not 200 with somebody else's data, and not the 500 an unidentified session would cause
        // if the route fell through to the controller.
        assertThat(webGet("/web/usage/interactions").statusCode()).isEqualTo(401);
        assertThat(webGet("/web/usage/stats").statusCode()).isEqualTo(401);
    }

    @Test
    void anAnonymousSearchIsNotRecorded() {
        insertSkill();

        assertThat(get("/api/v1/skills?q=pdf-tools", null).statusCode()).isEqualTo(401);

        assertThat(count("SELECT count(*) FROM activity_event")).isZero();
    }

    @Test
    void theWindowAndSkillAreEchoedAndCounted() {
        insertSkill();
        String token = token();
        get("/api/v1/skills?q=pdf-tools", token);
        assertThat(get(DEMO_SKILL + "/body", token).statusCode()).isEqualTo(200);

        String since = Timestamps.format(java.time.Instant.now().minusSeconds(86_400));
        String until = Timestamps.format(java.time.Instant.now().plusSeconds(86_400));
        JsonNode page = body(get(INTERACTIONS + "?since=" + since + "&until=" + until
                + "&skill=PDF", token));
        assertThat(page.get("window_days").asInt()).isEqualTo(2);
        assertThat(page.get("since").asText()).isEqualTo(since);
        assertThat(page.get("until").asText()).isEqualTo(until);
        assertThat(page.get("skill").asText()).isEqualTo("PDF");
        assertThat(page.get("has_any").asBoolean()).as("this account has rows").isTrue();
        assertThat(page.get("total").asLong()).as("the search that returned it").isEqualTo(1);

        JsonNode stats = body(get("/api/v1/usage/stats?since=" + since + "&until=" + until
                + "&skill=PDF", token));
        assertThat(stats.get("skill").asText()).isEqualTo("PDF");
        assertThat(stats.get("searches").asLong()).isEqualTo(1);
        assertThat(stats.get("calls").asLong()).isEqualTo(1);
    }

    @Test
    void theOwnerSeesACollaboratorsCallsAndSubmissionsInsideTheWindow() {
        insertSkill();
        String token = token();
        String skillId = jdbc.sql("SELECT id FROM skill WHERE name = 'pdf-tools'")
                .query(String.class).single();
        String collaborator = "01M3HTG7GDQ71Q28CCP7J0HM8T"; // the seeded `other` account
        String at = Timestamps.now();
        jdbc.sql("INSERT INTO skill_grant (skill_id, grantee_id, role, granted_by, created_at)"
                        + " VALUES (:skill, :grantee, 'browse', :by, :at)")
                .param("skill", skillId).param("grantee", collaborator)
                .param("by", SUBJECT_USER_ID).param("at", at).update();

        String inside = Timestamps.format(java.time.Instant.now().minusSeconds(600));
        String outside = Timestamps.format(java.time.Instant.now().minusSeconds(3600));
        insertInvokeFor(collaborator, skillId, inside);
        insertInvokeFor(collaborator, skillId, inside);
        insertInvokeFor(collaborator, skillId, outside);
        jdbc.sql("INSERT INTO audit_event (actor_user_id, action, target_type, target_id, at)"
                        + " VALUES (:actor, 'submit', 'skill', :skill, :at)")
                .param("actor", collaborator).param("skill", skillId).param("at", inside).update();

        String since = Timestamps.format(java.time.Instant.now().minusSeconds(1800));
        String until = Timestamps.format(java.time.Instant.now().plusSeconds(3600));
        JsonNode usage = body(get("/api/v1/usage/skills/demo/pdf-tools?since=" + since
                + "&until=" + until, token));

        assertThat(usage.get("totals").get("calls").asText()).isEqualTo("2");
        assertThat(usage.get("totals").get("accounts").asText()).isEqualTo("1");
        JsonNode row = usage.get("users").get(1);
        assertThat(usage.get("users").get(0).get("owner").asBoolean())
                .as("the owner row comes first").isTrue();
        assertThat(row.get("who").asText()).isEqualTo("other");
        assertThat(row.get("invoke").asLong()).as("the two calls inside the window").isEqualTo(2);
        assertThat(row.get("submitted").asLong()).isEqualTo(1);
        assertThat(row.get("last").asText()).isEqualTo(inside);

        // The same rows seen through a wider window include the call before it.
        String wider = Timestamps.format(java.time.Instant.now().minusSeconds(7200));
        JsonNode all = body(get("/api/v1/usage/skills/demo/pdf-tools?since=" + wider
                + "&until=" + until, token));
        assertThat(all.get("totals").get("calls").asText()).isEqualTo("3");
    }

    @Test
    void theWebPlaneReadsTheSameRowsAsTheApiPlane() {
        insertSkill();
        String token = token();
        get("/api/v1/skills?q=pdf-tools", token);
        assertThat(get(DEMO_SKILL + "/body", token).statusCode()).isEqualTo(200);

        String since = Timestamps.format(java.time.Instant.now().minusSeconds(86_400));
        String until = Timestamps.format(java.time.Instant.now().plusSeconds(86_400));
        String query = "?since=" + since + "&until=" + until + "&page=1&limit=10";

        JsonNode api = body(get(INTERACTIONS + query, token));
        JsonNode web = body(webGet("/web/usage/interactions" + query));

        assertThat(web.get("total")).isEqualTo(api.get("total"));
        assertThat(web.get("items")).isEqualTo(api.get("items"));
        assertThat(web.get("window_days")).isEqualTo(api.get("window_days"));
        assertThat(web.get("since")).isEqualTo(api.get("since"));
        assertThat(web.get("until")).isEqualTo(api.get("until"));
        assertThat(web.get("has_any")).isEqualTo(api.get("has_any"));
    }

    @Test
    void anAccountWithNothingYetSaysSo() {
        String token = token();

        JsonNode page = body(get(INTERACTIONS, token));
        assertThat(page.get("total").asLong()).isZero();
        assertThat(page.get("has_any").asBoolean())
                .as("no records at all, which is a different sentence from 'filters kept nothing'")
                .isFalse();

        // The same call with a keyword still reports the account's own emptiness, not the filter's.
        JsonNode filtered = body(get(INTERACTIONS + "?skill=zzz", token));
        assertThat(filtered.get("total").asLong()).isZero();
        assertThat(filtered.get("has_any").asBoolean()).isFalse();
    }

    @Test
    void aWindowTheServerCannotUseIsA400WithTheReason() {
        insertSkill();
        String token = token();
        String since = Timestamps.format(java.time.Instant.now().minusSeconds(3600));
        String until = Timestamps.format(java.time.Instant.now().plusSeconds(3600));

        HttpResponse<String> missing = get(INTERACTIONS + "?since=" + since, token);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("since 与 until");

        HttpResponse<String> backwards = get(INTERACTIONS + "?since=" + until + "&until=" + since,
                token);
        assertThat(backwards.statusCode()).isEqualTo(400);
        assertThat(backwards.body()).contains("until 要晚于 since");
    }

    @Test
    void theOwnerViewIsTheWindowNotTheCumulativeSignal() {
        insertSkill();
        String token = token();
        String skillId = jdbc.sql("SELECT id FROM skill WHERE name = 'pdf-tools'")
                .query(String.class).single();
        String older = Timestamps.format(java.time.Instant.now().minusSeconds(3600));
        String newer = Timestamps.format(java.time.Instant.now().minusSeconds(600));
        String since = Timestamps.format(java.time.Instant.now().minusSeconds(1800));
        String until = Timestamps.format(java.time.Instant.now().plusSeconds(3600));
        insertInvoke(skillId, older);
        insertInvoke(skillId, newer);

        // Only the newer call falls inside this window.
        JsonNode usage = body(get("/api/v1/usage/skills/demo/pdf-tools?since=" + since
                + "&until=" + until, token));
        assertThat(usage.get("window_days").asInt()).isEqualTo(1);
        assertThat(usage.get("totals").get("calls").asText()).isEqualTo("1");
        assertThat(usage.get("users").get(0).get("invoke").asLong()).isEqualTo(1);

        JsonNode mine = body(get("/api/v1/usage/skills/demo/pdf-tools/mine?since=" + since
                + "&until=" + until, token));
        assertThat(mine.get("calls").asLong()).isEqualTo(1);
    }

    @Test
    void aPublicSkillBucketsTheWindowAggregate() {
        insertSkill();
        String token = token();
        String skillId = jdbc.sql("SELECT id FROM skill WHERE name = 'pdf-tools'")
                .query(String.class).single();
        jdbc.sql("UPDATE skill SET visibility = 'public' WHERE id = :id")
                .param("id", skillId).update();
        String now = Timestamps.now();
        for (int i = 0; i < 12; i++) {
            insertInvoke(skillId, now);
        }

        String since = Timestamps.format(java.time.Instant.now().minusSeconds(3600));
        String until = Timestamps.format(java.time.Instant.now().plusSeconds(3600));
        JsonNode usage = body(get("/api/v1/usage/skills/demo/pdf-tools?since=" + since
                + "&until=" + until, token));
        assertThat(usage.get("public").asBoolean()).isTrue();
        assertThat(usage.get("totals").get("calls").asText()).isEqualTo("10+");
        assertThat(usage.get("totals").get("accounts").asText()).isEqualTo("少于 10 个账号");
        assertThat(usage.get("totals").get("last_used").asText())
                .as("the public view keeps the instant; the client picks the display day")
                .matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(usage.get("users")).as("the public view carries no collaborator rows").isEmpty();

        JsonNode users = body(get("/api/v1/usage/skills/demo/pdf-tools/users?since=" + since
                + "&until=" + until, token));
        assertThat(users.get("public").asBoolean()).as("the CLI can tell this apart from unused")
                .isTrue();
        assertThat(users.get("users")).isEmpty();
    }

    /**
     * The fixture {@code SkillContentIT} uses, reduced to the one file these tests read: a live
     * private skill owned by the seeded account, with one published version.
     */
    private void insertSkill() {
        String skillId = Ulid.generate();
        String versionId = Ulid.generate();
        String at = Timestamps.now();
        byte[] body = SKILL_MD.getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(body);

        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, current_version_id, current_version_name,
                                   created_by, created_at, updated_at)
                VALUES (:id, :namespace, 'pdf-tools', 'PDF tools', 'Extract and merge PDFs',
                        '{"name":"pdf-tools","description":"Extract and merge PDFs"}',
                        'private', :version, '1.0.0', :user, :at, :at)
                """)
                .param("id", skillId).param("namespace", DEMO_NAMESPACE_ID)
                .param("version", versionId).param("user", SUBJECT_USER_ID).param("at", at)
                .update();
        jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, version, digest, file_count,
                                           total_bytes, changelog, source, submitted_by,
                                           submitted_at, state, state_at, first_published_at,
                                           title, description, frontmatter)
                VALUES (:id, :skill, 1, '1.0.0', :digest, 1, :total, '', 'zip', :user,
                        :at, 'published', :at, :at, 'PDF tools', 'Extract and merge PDFs',
                        '{"name":"pdf-tools","description":"Extract and merge PDFs"}')
                """)
                .param("id", versionId).param("skill", skillId).param("digest", sha)
                .param("total", body.length).param("user", SUBJECT_USER_ID).param("at", at)
                .update();
        jdbc.sql("INSERT INTO blob (sha256, size, created_at) VALUES (:sha, :size, :at)")
                .param("sha", sha).param("size", body.length).param("at", at).update();
        jdbc.sql("INSERT INTO blob_content (sha256, bytes) VALUES (:sha, :bytes)")
                .param("sha", sha).param("bytes", body).update();
        jdbc.sql("""
                INSERT INTO version_file (version_id, relpath, blob_sha256, size, is_binary)
                VALUES (:version, 'SKILL.md', :sha, :size, 0)
                """)
                .param("version", versionId).param("sha", sha).param("size", body.length)
                .update();
    }

    private void insertInvoke(String skillId, String at) {
        insertInvokeFor(SUBJECT_USER_ID, skillId, at);
    }

    private void insertInvokeFor(String actor, String skillId, String at) {
        jdbc.sql("""
                INSERT INTO activity_event (actor_user_id, kind, skill_id, skill_address,
                                            version_label, target, detail, at)
                VALUES (:actor, 'invoke', :skill, 'demo/pdf-tools', '1.0.0', NULL, '{}', :at)
                """)
                .param("actor", actor).param("skill", skillId).param("at", at).update();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE", e);
        }
    }
}
