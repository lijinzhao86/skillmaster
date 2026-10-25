package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
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
 * {@code POST /web/skills/publish-batch} (v18, ADR 0090): one row at a time, each its own answer.
 *
 * <p><strong>What this asserts is the protocol, not the timing.</strong> The lines carry the order the
 * work happened in and every row's own result; whether they arrived while the work was still going is
 * a fact about the transport that this client cannot see (it reads the body to the end). The page's
 * own test asserts that each row lands as its line arrives.
 *
 * <p><strong>A refusal of the request is not a row.</strong> An empty batch and a skill named twice
 * are answered before the first line is written, because once a line is out the status has been sent
 * — so those are asserted as ordinary 4xx with nothing published.
 */
@Sql("/sql/truncate-business-tables.sql")
class PublishBatchIT extends AbstractAccountIT {

    private static final String BATCH = "/web/skills/publish-batch";

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
    void eachRowLandsWithItsOwnAnswerAndSuccessesSurviveLaterFailures() {
        String good = submit("pdf-tools", "第一份", "minor");
        submit("other-skill", "第一份", null);
        String noSuchPin = "ZZZZZZZZ";

        List<JsonNode> lines = batch(List.of(
                target("pdf-tools", good, "1.0.0"),
                target("other-skill", noSuchPin, null)));

        assertThat(lines).hasSize(4);
        assertThat(lines.get(0).get("type").asText()).isEqualTo("start");
        assertThat(lines.get(0).get("total").asInt()).isEqualTo(2);

        assertThat(lines.get(1).get("type").asText()).isEqualTo("row");
        assertThat(lines.get(1).get("ok").asBoolean()).isTrue();
        assertThat(lines.get(1).get("version").asText())
                .as("a skill that has never been published gets 1.0.0, and the type does not take part")
                .isEqualTo("1.0.0");

        assertThat(lines.get(2).get("ok").asBoolean()).isFalse();
        assertThat(lines.get(2).get("code").asText()).isEqualTo("skill_not_found");

        assertThat(lines.get(3).get("type").asText()).isEqualTo("done");
        assertThat(lines.get(3).get("ok").asInt()).isEqualTo(1);
        assertThat(lines.get(3).get("failed").asInt()).isEqualTo(1);

        // **逐条成功或失败**：the row that went live stays live although the one after it failed.
        assertThat(liveVersionOf("pdf-tools")).isEqualTo("1.0.0");
    }

    @Test
    void theNumberComesFromTheTypeAndTheLiveVersion() {
        String first = submit("typed", "第一版", null);
        publishNow("typed", first, "1.2.3");
        String second = submit("typed", "第二版", "minor");

        List<JsonNode> lines = batch(List.of(target("typed", second, "1.3.0")));

        assertThat(lines.get(1).get("ok").asBoolean()).as(lines.get(1).toString()).isTrue();
        assertThat(lines.get(1).get("version").asText()).isEqualTo("1.3.0");
        assertThat(lines.get(1).get("predicted").asText())
                .as("the prediction is echoed back so a page can be contradicted")
                .isEqualTo("1.3.0");
    }

    @Test
    void aPrereleaseBaselineAsksForANumberRatherThanGuessingOne() {
        String first = submit("rc", "第一版", null);
        publishNow("rc", first, "1.0.0-rc.1");
        String second = submit("rc", "第二版", "patch");

        List<JsonNode> lines = batch(List.of(target("rc", second, null)));

        assertThat(lines.get(1).get("ok").asBoolean()).isFalse();
        assertThat(lines.get(1).get("code").asText()).isEqualTo("release_type_needs_explicit_name");
        assertThat(liveVersionOf("rc")).as("nothing was published").isEqualTo("1.0.0-rc.1");

        // **The second step is the whole point of the refusal** (T06): the publisher types a number
        // and the same draft goes out. A test that stopped at the 400 would prove the door is
        // closed, not that the way in exists.
        publishNow("rc", second, "1.0.0");
        assertThat(liveVersionOf("rc")).isEqualTo("1.0.0");
    }

    @Test
    void aTargetThisCallerCannotPublishFailsAsItsOwnRowAndNotAsTheWholeBatch() {
        // A `write` grantee may add a draft and not publish one. The queue never lists it (M12 filters
        // by rung), so this is the window where the grant changed after the page opened — and the row
        // has to say so instead of the request answering 403.
        String ownerPhone = randomPhone();
        String owner = randomUsername();
        String ownerToken = tokenFor(registerAndSignIn(owner, ownerPhone));
        String draft = submitAs(ownerToken, owner, "their-skill", "第一份", "minor");
        assertThat(grantAs(ownerToken, owner, username, "write").statusCode()).isEqualTo(201);
        doubleSignIn();

        HttpResponse<String> response = webPost(BATCH,
                json(Map.of("targets", List.of(target(owner + "/their-skill")))), csrfToken());

        assertThat(response.statusCode())
                .as("one row's refusal is not the batch's: %s", response.body())
                .isEqualTo(200);
        List<JsonNode> lines = parse(response);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1).get("ok").asBoolean()).isFalse();
        assertThat(lines.get(1).get("code").asText()).isEqualTo("forbidden");
        assertThat(lines.get(2).get("failed").asInt()).isEqualTo(1);
    }

    @Test
    void aFirstPublishIsOneZeroZeroEvenWithNoTypeDeclared() {
        // **The queue's most ordinary row**: a brand-new skill whose draft declares nothing. The
        // page shows `1.0.0` and lets it be picked, so refusing it here would make a publish the
        // page called ready fail with a code about a missing type — a number was never needed, the
        // skill simply has no history to continue.
        String draft = submit("first-timer", "初版", null);

        List<JsonNode> lines = batch(List.of(target("first-timer", draft, "1.0.0")));

        assertThat(lines.get(1).get("ok").asBoolean()).as(lines.get(1).toString()).isTrue();
        assertThat(lines.get(1).get("version").asText()).isEqualTo("1.0.0");
        assertThat(liveVersionOf("first-timer")).isEqualTo("1.0.0");
    }

    @Test
    void anExplicitNumberWinsOverTheType() {
        // T05: the type says minor (1.3.0), the request says 1.9.0. The request is the answer —
        // somebody who typed a number has made a more specific decision than a kind.
        //
        // **This is the single publish route, not the batch**: a batch never names numbers (TD
        // §接口 — it carries only a pin and a prediction), so "explicit wins" is not expressible
        // there at all. A batch can only agree with the page's prediction.
        String first = submit("explicit", "第一版", null);
        publishNow("explicit", first, "1.2.3");
        String second = submit("explicit", "第二版", "minor");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", second);
        body.put("release_name", "1.9.0");
        HttpResponse<String> response =
                webPost("/web/skills/" + username + "/explicit/publish", json(body));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(liveVersionOf("explicit")).isEqualTo("1.9.0");
    }

    @Test
    void aComputedNumberThatIsTakenFailsAndIsNotPushedToTheNextOne() {
        // T07: 1.2.4 already exists somewhere in this skill's history, so a patch from 1.2.3 has
        // nowhere to go. **不顺延**: 1.2.5 is not invented — the number is a contract, and a system
        // that quietly moved on would publish something nobody chose.
        String first = submit("taken", "第一版", null);
        publishNow("taken", first, "1.2.3");
        String skillId = jdbc.sql("SELECT id FROM skill WHERE name = 'taken'")
                .query(String.class).single();
        insertVersionRow(skillId, "1.2.4");
        String second = submit("taken", "第二版", "patch");

        List<JsonNode> lines = batch(List.of(target("taken", second, "1.2.4")));

        assertThat(lines.get(1).get("ok").asBoolean()).isFalse();
        assertThat(lines.get(1).get("code").asText()).isEqualTo("version_already_exists");
        assertThat(liveVersionOf("taken")).as("nothing moved").isEqualTo("1.2.3");
        assertThat(count("SELECT count(*) FROM skill_version WHERE version = '1.2.5'"))
                .as("and the next number was not invented")
                .isZero();
    }

    @Test
    void aDraftThatCarriesItsOwnNumberKeepsIt() {
        // T22: a row from before drafts were numberless. Publishing it uses its number — the type
        // and the baseline never take part — and naming a different one is refused rather than
        // ignored, because the row would otherwise change identity silently.
        String draft = submit("legacy", "旧草稿", "patch");
        jdbc.sql("UPDATE skill_version SET version = '0.3.1' WHERE id = :id")
                .param("id", draft).update();

        List<JsonNode> lines = batch(List.of(target("legacy", draft, null)));
        assertThat(lines.get(1).get("ok").asBoolean()).as(lines.get(1).toString()).isTrue();
        assertThat(lines.get(1).get("version").asText()).isEqualTo("0.3.1");

        // Naming a **different** number for such a draft is refused rather than obeyed. It has to
        // be the single route: a batch never names numbers at all (TD §接口), so the only way to
        // ask is the version page's own request.
        String another = submit("legacy-two", "旧草稿", null);
        jdbc.sql("UPDATE skill_version SET version = '0.3.1' WHERE id = :id")
                .param("id", another).update();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", another);
        body.put("release_name", "0.3.2");
        HttpResponse<String> refused = webPost(
                "/web/skills/" + username + "/legacy-two/publish", json(body));
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(JSON.readTree(refused.body()).get("error").get("code").asText())
                .isEqualTo("version_name_is_assigned");
        assertThat(liveVersionOf("legacy-two")).isNull();
    }

    @Test
    void aLiveVersionWithNoNumberAsksForOneRatherThanGuessing() {
        // The other "unusable baseline": the skill is live, but that version carries no number. Every
        // submission since v6 writes `NULL` here, so this row can only be made by touching the
        // database (T22 does the same thing for the console). Same code as the prerelease case,
        // because the publisher's next step is the same — type in a number.
        String first = submit("numberless", "第一版", null);
        publishNow("numberless", first, "1.0.0");
        jdbc.sql("""
                UPDATE skill_version SET version = ''
                WHERE id = (SELECT current_version_id FROM skill WHERE name = 'numberless')
                """).update();
        String second = submit("numberless", "第二版", "patch");

        List<JsonNode> lines = batch(List.of(target("numberless", second, null)));

        assertThat(lines.get(1).get("ok").asBoolean()).isFalse();
        assertThat(lines.get(1).get("code").asText()).isEqualTo("release_type_needs_explicit_name");

        // And the way in works: a typed number publishes the same draft.
        publishNow("numberless", second, "1.0.0");
        assertThat(liveVersionOf("numberless")).isEqualTo("1.0.0");
    }

    @Test
    void aLiveVersionThatIsNotAVersionAtAllAsksForOneToo() {
        // The third unusable shape (PRD 验收 6): not empty, not a prerelease — simply not a version.
        // `SemVer.core` refuses it, so the type cannot make a number; the publisher types one.
        String first = submit("not-a-version", "第一版", null);
        publishNow("not-a-version", first, "1.0.0");
        jdbc.sql("""
                UPDATE skill_version SET version = '1.0'
                WHERE id = (SELECT current_version_id FROM skill WHERE name = 'not-a-version')
                """).update();
        String second = submit("not-a-version", "第二版", "patch");

        List<JsonNode> lines = batch(List.of(target("not-a-version", second, null)));

        assertThat(lines.get(1).get("ok").asBoolean()).isFalse();
        assertThat(lines.get(1).get("code").asText())
                .isEqualTo("release_type_needs_explicit_name");

        publishNow("not-a-version", second, "2.0.0");
        assertThat(liveVersionOf("not-a-version")).isEqualTo("2.0.0");
    }

    @Test
    void theBatchRouteIsCsrfProtected() {
        String draft = submit("guarded", "第一份", "patch");

        // No CSRF header: the request is refused before any row, exactly as the rest of `/web/**`
        // treats a write without the double-submit pair.
        HttpResponse<String> response = webPost(BATCH,
                json(Map.of("targets", List.of(target("guarded", draft, null)))),
                "not-the-token");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(liveVersionOf("guarded")).isNull();
    }

    @Test
    void oneSkillTwiceIsRefusedBeforeTheFirstRow() {
        String first = submit("pdf-tools", "第一份", "patch");
        String second = submit("pdf-tools", "第二份", "patch");

        HttpResponse<String> response = webPost(BATCH, json(Map.of("targets", List.of(
                target("pdf-tools", first, null),
                target("pdf-tools", second, null)))), csrfToken());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("error").get("code").asText())
                .isEqualTo("duplicate_target");
        assertThat(response.body()).as("no row was written, so the body is an envelope, not NDJSON")
                .doesNotContain("\"type\"");
        assertThat(liveVersionOf("pdf-tools")).as("one skill, published zero times").isNull();
    }

    @Test
    void anEmptyBatchIsRefusedTheSameWay() {
        HttpResponse<String> response =
                webPost(BATCH, json(Map.of("targets", List.of())), csrfToken());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("error").get("code").asText())
                .isEqualTo("invalid_request");
    }

    @Test
    void aTargetThatIsNullIsTheCallersMistakeNotOurFault() {
        // `{"targets":[null]}` is a malformed body. Reading `version` off the null element would
        // have thrown before any guard ran and answered 500 — a server fault blamed on the caller.
        // It is the same mistake as a missing `version`, so it gets the same 400.
        HttpResponse<String> response =
                webPost(BATCH, "{\"targets\":[null]}", csrfToken());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("error").get("code").asText())
                .isEqualTo("invalid_request");
        assertThat(field(response)).isEqualTo("targets");
        assertThat(issue(response)).isEqualTo("invalid_version");
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** The body's lines, parsed. The client reads it to the end, which is why timing is not asserted. */
    private List<JsonNode> batch(List<Map<String, Object>> targets) {
        HttpResponse<String> response = webPost(BATCH, json(Map.of("targets", targets)), csrfToken());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/x-ndjson");
        return parse(response);
    }

    private List<JsonNode> parse(HttpResponse<String> response) {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : response.body().split("\n")) {
            if (!line.isBlank()) {
                lines.add(JSON.readTree(line));
            }
        }
        return lines;
    }

    private Map<String, Object> target(String skill, String code, String predicted) {
        Map<String, Object> target = target(username + "/" + skill, code);
        if (predicted != null) {
            target.put("predicted", predicted);
        }
        return target;
    }

    /** `handle/skill` → namespace and name; the batch's own spelling. */
    private Map<String, Object> target(String address) {
        return target(address, submitCodeOf(address));
    }

    private Map<String, Object> target(String address, String code) {
        String[] parts = address.split("/");
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("namespace", parts[0]);
        target.put("name", parts[1]);
        target.put("version", code);
        return target;
    }

    /** The newest draft's code for an address — what a page would have in hand. */
    private String submitCodeOf(String address) {
        String[] parts = address.split("/");
        return jdbc.sql("""
                SELECT v.id FROM skill_version v
                JOIN skill s ON s.id = v.skill_id
                WHERE s.name = :name AND v.state = 'draft'
                ORDER BY v.number DESC LIMIT 1
                """)
                .param("name", parts[1]).query(String.class).single();
    }

    /**
     * The number the skill points at, or null when nothing is live.
     *
     * <p>Read from the row rather than over HTTP: the detail response's shape is another test's
     * subject, and a helper that had to know it would turn a wrong shape into a failure here.
     */
    private String liveVersionOf(String skillName) {
        return jdbc.sql("SELECT current_version_name FROM skill WHERE name = :name")
                .param("name", skillName).query(String.class).optional().orElse(null);
    }

    /** Signed back in as this test's own account — registering the owner re-pointed the jar at them. */
    private void doubleSignIn() {
        assertThat(signIn(phone, PASSWORD).statusCode()).as("signing back in").isEqualTo(200);
    }

    private HttpResponse<String> grantAs(String ownerToken, String owner, String handle, String role) {
        return send(request("/api/v1/skills/" + owner + "/their-skill/grants", ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("handle", handle, "role", role))))
                .build());
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

    /**
     * A discarded version row that already holds a name — how 「1.2.4 已被占用」 is made.
     *
     * <p>Discarded because the name is what matters: a number is never reused whatever the state
     * says, and `discarded` is the one state whose pairing constraints a fixture can satisfy without
     * pretending a version went live.
     */
    private void insertVersionRow(String skillId, String version) {
        jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, version, digest, file_count,
                                           total_bytes, changelog, source, submitted_by,
                                           submitted_at, state, state_at, first_published_at,
                                           title, description, frontmatter)
                VALUES (:id, :skill,
                        (SELECT COALESCE(MAX(number), 0) + 1 FROM skill_version WHERE skill_id = :skill),
                        :version, :digest, 0, 0, '', 'zip', :user, :at,
                        'discarded', :at, NULL, '', 'd', '{}')
                """)
                .param("id", com.skillmasterai.common.Ulid.generate())
                .param("skill", skillId)
                .param("version", version)
                .param("digest", "sha256:" + com.skillmasterai.common.Ulid.generate())
                .param("user", userId)
                .param("at", com.skillmasterai.common.Timestamps.now())
                .update();
    }

    /** Publishes one draft the ordinary way, to set up a live baseline. */
    private void publishNow(String skillName, String draftCode, String releaseName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", draftCode);
        body.put("release_name", releaseName);
        HttpResponse<String> response =
                webPost("/web/skills/" + username + "/" + skillName + "/publish", json(body));
        assertThat(response.statusCode()).as("setup publish failed: %s", response.body())
                .isEqualTo(200);
    }
}
