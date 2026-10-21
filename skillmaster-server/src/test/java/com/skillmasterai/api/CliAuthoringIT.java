package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The CLI's authoring half on {@code /api/v1/author} (v10): creating a skill from nothing, reading
 * the base a first write would start from, and creating a draft together with that first change.
 *
 * <p>The shapes asserted here are the ones the CLI is written against; the rules behind them
 * (one transaction, content addressing, the ladder) are the v8/v9 ones, so these tests are about the
 * new doors rather than re-proving the rooms.
 */
@Sql("/sql/truncate-business-tables.sql")
class CliAuthoringIT extends AbstractAccountIT {

    private static final String AUTHOR = "/api/v1/author/skills";

    private String username;
    private String token;

    @BeforeEach
    void signUp() {
        username = randomUsername();
        token = tokenFor(registerAndSignIn(username, randomPhone()));
    }

    @Test
    void creatingASkillAlsoCreatesItsFirstDraft() {
        HttpResponse<String> created = createSkill("hello", "第一个 skill", "第一版");

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode body = JSON.readTree(created.body());
        assertThat(body.get("namespace").asText()).isEqualTo(username);
        assertThat(body.get("name").asText()).isEqualTo("hello");
        assertThat(body.get("version_name").asText()).isEqualTo("第一版");
        assertThat(body.get("state").asText()).isEqualTo("draft");
        String code = body.get("version_code").asText();

        assertThat(count("SELECT COUNT(*) FROM skill WHERE name = 'hello'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM skill_version WHERE skill_id = "
                + "(SELECT id FROM skill WHERE name = 'hello')")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM skill WHERE name = 'hello'"
                + " AND current_version_id IS NULL")).isEqualTo(1);

        JsonNode files = JSON.readTree(get(path("hello") + "/versions/" + code + "/files", token)
                .body()).get("files");
        assertThat(files).hasSize(1);
        assertThat(files.get(0).get("relpath").asText()).isEqualTo("SKILL.md");

        String skillMd = send(request(path("hello") + "/versions/" + code + "/files/SKILL.md",
                token).GET().build()).body();
        assertThat(skillMd).contains("name: \"hello\"").contains("第一个 skill");
    }

    @Test
    void anExistingOrDeletedNameIsANamedConflictWithZeroWrites() {
        assertThat(createSkill("hello", "one", "第一版").statusCode()).isEqualTo(201);
        long[] before = rows();

        HttpResponse<String> second = createSkill("hello", "two", "第一版");
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(second.body()).get("error").get("code").asText())
                .isEqualTo("skill_already_exists");
        assertThat(rows()).isEqualTo(before);

        assertThat(webPost("/web/skills/" + username + "/hello/delete", "").statusCode())
                .isEqualTo(204);
        long[] afterDelete = rows();

        HttpResponse<String> deleted = createSkill("hello", "three", "第一版");
        assertThat(deleted.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(deleted.body()).get("error").get("code").asText())
                .isEqualTo("skill_deleted");
        assertThat(rows()).isEqualTo(afterDelete);
    }

    @Test
    void badFieldsAreRefusedByTheirOwnCodes() {
        HttpResponse<String> noDescription = createSkill("hello", "", "第一版");
        assertThat(noDescription.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(noDescription.body()).get("error").get("details").get(0)
                .get("issue").asText()).isEqualTo("required_field_missing");

        HttpResponse<String> atName = createSkill("he@llo", "d", "第一版");
        assertThat(atName.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(atName.body()).get("error").get("details").get(0)
                .get("issue").asText()).isEqualTo("name_contains_version_separator");

        HttpResponse<String> longVersion = createSkill("hello", "d", "x".repeat(41));
        assertThat(longVersion.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(longVersion.body()).get("error").get("code").asText())
                .isEqualTo("draft_name_too_long");

        HttpResponse<String> emptyVersion = createSkill("hello2", "d", "");
        assertThat(emptyVersion.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(emptyVersion.body()).get("error").get("code").asText())
                .isEqualTo("draft_name_required");
    }

    @Test
    void theBaseIsTheLiveVersion() {
        String draftId = submitDraft("first", Map.of("SKILL.md",
                "---\nname: pdf-tools\ndescription: d\n---\n"));
        publish(draftId, "1.0.0");

        HttpResponse<String> base = base("pdf-tools");
        assertThat(base.statusCode()).as(base.body()).isEqualTo(200);
        JsonNode node = JSON.readTree(base.body()).get("base");
        assertThat(node.get("pin").asText()).isEqualTo("1.0.0");
        assertThat(node.get("digest").asText()).startsWith("sha256:");
    }

    @Test
    void theBaseFallsBackToADraftThenToBlank() {
        String draftId = submitDraft("first", Map.of("SKILL.md",
                "---\nname: pdf-tools\ndescription: d\n---\n"));

        JsonNode draftBase = JSON.readTree(base("pdf-tools").body()).get("base");
        assertThat(draftBase.get("pin").asText()).isEqualTo(draftId);

        assertThat(discard(draftId).statusCode()).isEqualTo(200);

        assertThat(JSON.readTree(base("pdf-tools").body()).get("base").isNull()).isTrue();
    }

    @Test
    void theDraftFallbackAnswersWithAReadableDraft() {
        String first = submitDraft("first", Map.of("SKILL.md",
                "---\nname: pdf-tools\ndescription: d\n---\nfirst"));
        String second = submitDraft("second", Map.of("SKILL.md",
                "---\nname: pdf-tools\ndescription: d\n---\nsecond"));

        HttpResponse<String> baseResponse = base("pdf-tools");
        JsonNode draftBase = JSON.readTree(baseResponse.body()).get("base");
        assertThat(draftBase != null && !draftBase.isNull())
                .as("base answered %s: %s", baseResponse.statusCode(), baseResponse.body())
                .isTrue();
        String pin = draftBase.get("pin").asText();
        // Which of the two is "newest" is the server's ordering; what S11 requires is that the
        // answer is one of the drafts and that it can be read back as a draft.
        assertThat(List.of(first, second)).contains(pin);

        HttpResponse<String> read =
                send(request(path("pdf-tools") + "/versions/" + pin, token).GET().build());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(JSON.readTree(read.body()).get("version").get("status").asText())
                .isEqualTo("draft");
    }

    @Test
    void theFirstWriteCreatesTheDraftWithItsChange() {
        String liveDraft = submitDraft("first", Map.of(
                "SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n",
                "a.md", "one"));
        publish(liveDraft, "1.0.0");
        JsonNode base = JSON.readTree(base("pdf-tools").body()).get("base");

        HttpResponse<String> created = createDraft("pdf-tools", Map.of(
                "name", "第二版",
                "from", base.get("pin").asText(),
                "from_digest", base.get("digest").asText(),
                "edit", Map.of("op", "write", "path", "a.md", "text", "two")));

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode version = JSON.readTree(created.body()).get("version");
        assertThat(version.get("draft_name").asText()).isEqualTo("第二版");
        assertThat(version.get("state").asText()).isEqualTo("draft");
        String code = version.get("code").asText();

        String changed = send(request(path("pdf-tools") + "/versions/" + code + "/files/a.md",
                token).GET().build()).body();
        assertThat(changed).isEqualTo("two");
        String untouched = send(request(path("pdf-tools") + "/versions/" + liveDraft
                + "/files/a.md", token).GET().build()).body();
        assertThat(untouched).isEqualTo("one");
    }

    @Test
    void identicalContentIsRefusedAndNothingIsWritten() {
        String draftId = submitDraft("first", Map.of("SKILL.md",
                "---\nname: pdf-tools\ndescription: d\n---\n"));
        publish(draftId, "1.0.0");
        JsonNode base = JSON.readTree(base("pdf-tools").body()).get("base");
        long[] before = rows();

        HttpResponse<String> refused = createDraft("pdf-tools", Map.of(
                "name", "same",
                "from", base.get("pin").asText(),
                "from_digest", base.get("digest").asText(),
                "edit", Map.of("op", "write", "path", "SKILL.md",
                        "text", "---\nname: pdf-tools\ndescription: d\n---\n")));

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(refused.body()).get("error").get("code").asText())
                .isEqualTo("content_already_exists");
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void aStaleDigestIs409AndAMissingOneIs428() {
        String draftId = submitDraft("first", Map.of("SKILL.md",
                "---\nname: pdf-tools\ndescription: d\n---\n"));
        JsonNode base = JSON.readTree(base("pdf-tools").body()).get("base");

        // Move the draft on, so the digest the base read names a state that is gone.
        HttpResponse<String> written = send(request(path("pdf-tools") + "/versions/" + draftId
                + "/files/a.md", token)
                .header(HttpHeaders.IF_MATCH, base.get("digest").asText())
                .PUT(HttpRequest.BodyPublishers.ofString("new"))
                .build());
        assertThat(written.statusCode()).as(written.body()).isEqualTo(200);

        HttpResponse<String> stale = createDraft("pdf-tools", Map.of(
                "name", "第二版",
                "from", draftId,
                "from_digest", base.get("digest").asText(),
                "edit", Map.of("op", "write", "path", "b.md", "text", "b")));
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(stale.body()).get("error").get("code").asText())
                .isEqualTo("digest_mismatch");

        HttpResponse<String> missing = createDraft("pdf-tools", Map.of(
                "name", "第二版",
                "from", draftId,
                "edit", Map.of("op", "write", "path", "b.md", "text", "b")));
        assertThat(missing.statusCode()).isEqualTo(428);
    }

    @Test
    void aBlankBaseCarriesNoDigestAndAFirstWriteStillNeedsItsName() {
        String draftId = submitDraft("first", Map.of("SKILL.md",
                "---\nname: pdf-tools\ndescription: d\n---\n"));
        assertThat(discard(draftId).statusCode()).isEqualTo(200);

        HttpResponse<String> blank = createDraft("pdf-tools", Map.of(
                "name", "空白起步",
                "edit", Map.of("op", "write", "path", "a.md", "text", "a")));
        assertThat(blank.statusCode()).as(blank.body()).isEqualTo(201);

        HttpResponse<String> noName = createDraft("pdf-tools", Map.of(
                "edit", Map.of("op", "write", "path", "b.md", "text", "b")));
        assertThat(noName.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(noName.body()).get("error").get("code").asText())
                .isEqualTo("draft_name_required");
    }

    @Test
    void aDiscardedBaseIs400ForMaintainAndAdminAnd404ForWrite() {
        String draftId = submitDraft("first", Map.of("SKILL.md",
                "---\nname: pdf-tools\ndescription: d\n---\n"));
        assertThat(discard(draftId).statusCode()).isEqualTo(200);

        for (String role : new String[] {"maintain", "admin"}) {
            String handle = randomUsername();
            String handleToken = tokenFor(registerAndSignIn(handle, randomPhone()));
            assertThat(share(token, handle, role).statusCode()).isEqualTo(201);
            HttpResponse<String> refused = send(request("/api/v1/author/skills/" + username
                    + "/pdf-tools/versions", handleToken)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json(Map.of(
                            "name", "从已丢弃", "from", draftId,
                            "edit", Map.of("op", "write", "path", "a.md", "text", "a")))))
                    .build());
            assertThat(refused.statusCode()).as(role + ": " + refused.body()).isEqualTo(400);
            assertThat(JSON.readTree(refused.body()).get("error").get("details").get(0)
                    .get("issue").asText()).isEqualTo("not_a_base");
        }

        // The same base at `write` is invisible rather than refused: that rung never sees a
        // discarded row, and §4.1's one 404 is the answer for "does not exist for you".
        String writer = randomUsername();
        String writerToken = tokenFor(registerAndSignIn(writer, randomPhone()));
        assertThat(share(token, writer, "write").statusCode()).isEqualTo(201);
        HttpResponse<String> invisible = send(request("/api/v1/author/skills/" + username
                + "/pdf-tools/versions", writerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of(
                        "name", "从已丢弃", "from", draftId,
                        "edit", Map.of("op", "write", "path", "a.md", "text", "a")))))
                .build());
        assertThat(invisible.statusCode()).isEqualTo(404);
    }

    @Test
    void renameAndDeleteCanBeTheFirstChange() {
        String live = submitDraft("first", Map.of(
                "SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n",
                "a.md", "one"));
        publish(live, "1.0.0");
        JsonNode base = JSON.readTree(base("pdf-tools").body()).get("base");

        HttpResponse<String> renamed = createDraft("pdf-tools", Map.of(
                "name", "改名", "from", base.get("pin").asText(),
                "from_digest", base.get("digest").asText(),
                "edit", Map.of("op", "rename", "from", "a.md", "to", "b.md")));
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(201);
        String renamedCode = JSON.readTree(renamed.body()).get("version").get("code").asText();
        assertThat(send(request(path("pdf-tools") + "/versions/" + renamedCode + "/files/b.md",
                token).GET().build()).body()).isEqualTo("one");

        HttpResponse<String> deleted = createDraft("pdf-tools", Map.of(
                "name", "删掉", "from", base.get("pin").asText(),
                "from_digest", base.get("digest").asText(),
                "edit", Map.of("op", "delete", "path", "a.md")));
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(201);
        String deletedCode = JSON.readTree(deleted.body()).get("version").get("code").asText();
        JsonNode files = JSON.readTree(get(path("pdf-tools") + "/versions/" + deletedCode
                + "/files", token).body()).get("files");
        assertThat(files).hasSize(1);
        assertThat(files.get(0).get("relpath").asText()).isEqualTo("SKILL.md");
    }

    @Test
    void theNoteLandsOnTheDraftAndPastTwoHundredIsNamed() {
        String live = submitDraft("first", Map.of("SKILL.md",
                "---\nname: pdf-tools\ndescription: d\n---\n"));
        publish(live, "1.0.0");
        JsonNode base = JSON.readTree(base("pdf-tools").body()).get("base");

        HttpResponse<String> created = createDraft("pdf-tools", Map.of(
                "name", "带说明", "note", "一句话",
                "from", base.get("pin").asText(), "from_digest", base.get("digest").asText(),
                "edit", Map.of("op", "write", "path", "a.md", "text", "a")));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String code = JSON.readTree(created.body()).get("version").get("code").asText();
        JsonNode shown = JSON.readTree(get(path("pdf-tools") + "/versions/" + code, token).body());
        assertThat(shown.get("version").get("note").asText()).isEqualTo("一句话");

        HttpResponse<String> tooLong = createDraft("pdf-tools", Map.of(
                "name", "说明太长", "note", "x".repeat(201),
                "from", base.get("pin").asText(), "from_digest", base.get("digest").asText(),
                "edit", Map.of("op", "write", "path", "b.md", "text", "b")));
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(tooLong.body()).get("error").get("code").asText())
                .isEqualTo("note_too_long");
    }

    @Test
    void aBlankBaseRejectsADigest() {
        String draftId = submitDraft("first", Map.of("SKILL.md",
                "---\nname: pdf-tools\ndescription: d\n---\n"));
        assertThat(discard(draftId).statusCode()).isEqualTo(200);

        HttpResponse<String> refused = createDraft("pdf-tools", Map.of(
                "name", "空白", "from_digest", "sha256:abc",
                "edit", Map.of("op", "write", "path", "a.md", "text", "a")));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(refused.body()).get("error").get("details").get(0)
                .get("issue").asText()).isEqualTo("unexpected");
    }

    @Test
    void anExplicitPublishedOrDraftVersionCanBeTheBase() {
        String first = submitDraft("first", Map.of(
                "SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n",
                "a.md", "one"));
        publish(first, "1.0.0");
        String second = submitDraft("second", Map.of(
                "SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n",
                "a.md", "two", "b.md", "second"));
        publish(second, "2.0.0");

        HttpResponse<String> fromHistory = createDraft("pdf-tools", Map.of(
                "name", "从历史", "from", "1.0.0",
                "from_digest", digestOf("1.0.0"),
                "edit", Map.of("op", "write", "path", "c.md", "text", "c")));
        assertThat(fromHistory.statusCode()).as(fromHistory.body()).isEqualTo(201);
        String historyCode = JSON.readTree(fromHistory.body()).get("version").get("code").asText();
        String a = send(request(path("pdf-tools") + "/versions/" + historyCode + "/files/a.md",
                token).GET().build()).body();
        assertThat(a).as("the base is the 1.0.0 line, not the live 2.0.0").isEqualTo("one");

        String draft = submitDraft("third", Map.of(
                "SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n",
                "a.md", "draft-base"));
        HttpResponse<String> fromDraft = createDraft("pdf-tools", Map.of(
                "name", "从草稿", "from", draft,
                "from_digest", digestOfDraft(draft),
                "edit", Map.of("op", "write", "path", "d.md", "text", "d")));
        assertThat(fromDraft.statusCode()).as(fromDraft.body()).isEqualTo(201);
        String draftCode = JSON.readTree(fromDraft.body()).get("version").get("code").asText();
        String draftBase = send(request(path("pdf-tools") + "/versions/" + draftCode
                + "/files/a.md", token).GET().build()).body();
        assertThat(draftBase).isEqualTo("draft-base");
    }

    // ------------------------------------------------------------------ helpers

    private long[] rows() {
        return new long[] {
            count("SELECT COUNT(*) FROM skill"),
            count("SELECT COUNT(*) FROM skill_version"),
            count("SELECT COUNT(*) FROM version_file"),
        };
    }

    private String path(String name) {
        return AUTHOR + "/" + username + "/" + name;
    }

    private HttpResponse<String> createSkill(String name, String description, String versionName) {
        return jsonPost(AUTHOR, Map.of("name", name, "description", description,
                "version_name", versionName));
    }

    private HttpResponse<String> base(String name) {
        return get(path(name) + "/draft-base", token);
    }

    private HttpResponse<String> createDraft(String name, Map<String, Object> body) {
        return jsonPost(path(name) + "/versions", body);
    }

    private HttpResponse<String> jsonPost(String path, Map<String, Object> body) {
        return send(request(path, token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body)))
                .build());
    }

    private HttpResponse<String> share(String shareToken, String handle, String role) {
        return send(request("/api/v1/skills/" + username + "/pdf-tools/grants", shareToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("handle", handle, "role", role))))
                .build());
    }

    private HttpResponse<String> discard(String draftId) {
        return jsonPost(path("pdf-tools") + "/versions/discard", Map.of("version", draftId));
    }

    private String submitDraft(String versionName, Map<String, String> files) {
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(files))
                .field("name", versionName);
        HttpResponse<String> response = send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
        assertThat(response.statusCode()).as("submit failed: %s", response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("version").get("draft_id").asText();
    }

    private String digestOf(String version) {
        return JSON.readTree(get(path("pdf-tools") + "/versions/" + version, token).body())
                .get("version").get("digest").asText();
    }

    private String digestOfDraft(String draftId) {
        return digestOf(draftId);
    }

    private void publish(String draftId, String releaseName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", draftId);
        body.put("release_name", releaseName);
        assertThat(webPost("/web/skills/" + username + "/pdf-tools/publish", json(body))
                .statusCode()).isEqualTo(200);
    }
}
