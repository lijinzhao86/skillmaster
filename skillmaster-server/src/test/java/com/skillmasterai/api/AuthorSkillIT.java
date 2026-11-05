package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.DraftProjection;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.VersionProjection;
import com.skillmasterai.modules.version.SkillDeletedException;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The author plane on {@code /api/v1} (ADR 0061): the CLI's version commands, end to end.
 *
 * <p>Its reads are the author's own — drafts, history and the row's derived status — and its writes
 * are the three that change nothing a caller sees: rename, note, discard, plus v37's narrow delete
 * of a skill that never went live (ADR 0127). Publishing, rolling back, revoking, restoring and the
 * skill switch deliberately have no route here, and this asserts that absence rather than trusting
 * the mapping not to grow one.
 */
@Sql("/sql/truncate-business-tables.sql")
class AuthorSkillIT extends AbstractAccountIT {

    private String username;
    private String token;

    /** v37: the projection write the publish/delete race test drives directly. */
    @Autowired
    private SkillMetadataService metadata;
    @Autowired
    private SkillVersionService versions;
    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    void signUp() {
        username = randomUsername();
        token = tokenFor(registerAndSignIn(username, randomPhone()));
    }

    @Test
    void theVersionListCarriesTheDraftAndTheDerivedStatus() {
        String draftId = submitDraft("compress 补文档", "把 compress 的用法补进正文");

        JsonNode body = JSON.readTree(get(path("/versions"), token).body());

        assertThat(body.get("skill").get("address").asText()).isEqualTo(username + "/pdf-tools");
        assertThat(body.get("versions")).hasSize(1);
        JsonNode row = body.get("versions").get(0);
        assertThat(row.get("status").asText()).isEqualTo("draft");
        assertThat(row.get("name").asText()).isEqualTo("compress 补文档");
        assertThat(row.get("note").asText()).isEqualTo("把 compress 的用法补进正文");
        assertThat(row.get("version").isNull()).as("a draft has no number").isTrue();
        assertThat(row.get("code").asText()).isEqualTo(draftId);
        assertThat(row.get("pin").asText()).as("a row id is the address, with no state prefix")
                .isEqualTo(draftId);
        assertThat(row.get("submitted_by").asText()).isEqualTo(username);
        assertThat(body.get("counts").get("all").asInt()).isEqualTo(1);
        assertThat(body.get("next").isNull()).isTrue();
    }

    @Test
    void aDraftCanBeRenamedAndGivenANoteThenBothFreezeAtPublish() {
        String draftId = submitDraft("原名", null);

        HttpResponse<String> renamed = send(request(path("/versions/" + draftId + "/name"),
                token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        json(Map.of("name", "新名字"))))
                .build());
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
        assertThat(JSON.readTree(renamed.body()).get("version").get("name").asText())
                .isEqualTo("新名字");

        HttpResponse<String> noted = send(request(path("/versions/" + draftId + "/note"),
                token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        json(Map.of("note", "补了按页与按分隔符"))))
                .build());
        assertThat(noted.statusCode()).as(noted.body()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'rename_draft'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'note_draft'"))
                .isEqualTo(1);

        publish(draftId, "1.0.0");

        HttpResponse<String> frozenName = send(request(
                path("/versions/1.0.0/name"), token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        json(Map.of("name", "late"))))
                .build());
        assertThat(frozenName.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(frozenName.body()).get("error").get("code").asText())
                .isEqualTo("draft_name_is_frozen");

        HttpResponse<String> frozenNote = send(request(path("/versions/1.0.0/note"), token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        json(Map.of("note", "late"))))
                .build());
        assertThat(frozenNote.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(frozenNote.body()).get("error").get("code").asText())
                .isEqualTo("note_is_frozen");
    }

    /**
     * A discarding write by a {@code write} caller answers with the row, not 404.
     *
     * <p>The row is {@code discarded} afterwards, which the read-visibility filter hides from
     * {@code write}; a response built by re-reading through that filter reported failure for a
     * write that had irreversibly succeeded.
     */
    @Test
    void discardingYourOwnDraftAnswersWithTheDiscardedRow() {
        String draftId = submitDraft("to discard", null);

        HttpResponse<String> discarded = send(request(path("/versions/discard"), token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("version", draftId))))
                .build());

        assertThat(discarded.statusCode()).as(discarded.body()).isEqualTo(200);
        JsonNode version = JSON.readTree(discarded.body()).get("version");
        assertThat(version.get("status").asText()).isEqualTo("discarded");
        assertThat(version.get("pin").asText())
                .as("the row keeps the address it was written and read by")
                .isEqualTo(draftId);

        // The same discard again is the same intent reached twice, and still a success.
        HttpResponse<String> again = send(request(path("/versions/discard"), token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("version", draftId))))
                .build());
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
    }

    @Test
    void theActionsThatChangeWhatCallersSeeHaveNoRouteHere() {
        String draftId = submitDraft("v", null);

        for (String verb : new String[] {"publish", "rollback", "revoke", "restore"}) {
            HttpResponse<String> response = send(request(
                    path("/versions/" + verb), token)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json(Map.of("version", draftId))))
                    .build());
            assertThat(response.statusCode())
                    .as("%s must be confirmed by a person in the browser (ADR 0031/0061)", verb)
                    .isEqualTo(404);
        }
    }

    /**
     * v37 (ADR 0127): the one delete a credential may perform — a skill that never had a version go
     * live. The name stays taken, which is ADR 0050 and the reason the page's restore exists.
     */
    @Test
    void aSkillThatWasNeverPublishedCanBeDeletedByItsOwner() {
        submitDraft("v", null);

        HttpResponse<String> response = send(request(path("/delete"), token)
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(get(path("/versions"), token).statusCode())
                .as("a deleted skill is not readable on the author plane").isEqualTo(404);
        assertThat(count("SELECT count(*) FROM skill WHERE deleted_at IS NOT NULL"))
                .as("soft delete: the row stays").isEqualTo(1);
    }

    @Test
    void aSkillThatHasBeenPublishedCannotBeDeletedByACredential() {
        String draftId = submitDraft("v", null);
        publish(draftId, "1.0.0");

        HttpResponse<String> response = send(request(path("/delete"), token)
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(JSON.readTree(response.body()).get("error").get("code").asText())
                .isEqualTo("skill_published");
        assertThat(count("SELECT count(*) FROM skill WHERE deleted_at IS NULL"))
                .as("nothing was deleted").isEqualTo(1);
    }

    /**
     * The condition is "ever published", not "is live now". A revoked version's `state` is
     * `revoked`, which is exactly the value a naive check would read as "never published".
     */
    @Test
    void publishingThenRevokingStillCountsAsPublished() {
        String draftId = submitDraft("v", null);
        publish(draftId, "1.0.0");
        // The state the risky implementation gets wrong: revoked, with no current version. The web
        // path refuses to revoke a *current* version, so the row is written directly — what the
        // delete must still refuse is "ever published", not "live now".
        jdbc.sql("""
                UPDATE skill_version SET state = 'revoked', revoked_reason = 'test'
                 WHERE id = :id
                """).param("id", draftId).update();
        jdbc.sql("UPDATE skill SET current_version_id = NULL WHERE name = 'pdf-tools'").update();

        HttpResponse<String> response = send(request(path("/delete"), token)
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(JSON.readTree(response.body()).get("error").get("code").asText())
                .isEqualTo("skill_published");
    }

    /** A collaborator who can read the skill is still not its owner: 403, not 404. */
    @Test
    void aCollaboratorWhoCanSeeTheSkillCannotDeleteIt() {
        submitDraft("v", null);
        String otherName = randomUsername();
        String otherId = registerAndSignIn(otherName, randomPhone());
        String otherToken = tokenFor(otherId);
        String skillId = jdbc.sql("SELECT id FROM skill WHERE name = 'pdf-tools'")
                .query(String.class).optional().orElseThrow();
        jdbc.sql("""
                INSERT INTO skill_grant (skill_id, grantee_id, role, granted_by, created_at)
                VALUES (:skill, :grantee, 'browse', :by, '2026-11-01T00:00:00Z')
                """)
                .param("skill", skillId).param("grantee", otherId).param("by", otherId)
                .update();

        assertThat(get(path("/versions"), otherToken).statusCode())
                .as("the grant makes it readable").isEqualTo(200);
        HttpResponse<String> response = send(request(path("/delete"), otherToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM skill WHERE deleted_at IS NULL")).isEqualTo(1);
    }

    /** A caller with no relationship to the skill gets the same 404 a nonexistent name does. */
    @Test
    void aStrangerGetsTheSameNotFoundAsANonexistentSkill() {
        submitDraft("v", null);
        String strangerToken = tokenFor(registerAndSignIn(randomUsername(), randomPhone()));

        HttpResponse<String> response = send(request(path("/delete"), strangerToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(JSON.readTree(response.body()).get("error").get("code").asText())
                .isEqualTo("skill_not_found");
        assertThat(count("SELECT count(*) FROM skill WHERE deleted_at IS NULL"))
                .as("nothing was deleted").isEqualTo(1);
    }

    /**
     * v37: a publish that lost the row to a delete cannot write the projection.
     *
     * <p>The publish and the delete race for the same row lock. The version may already be marked
     * published inside the publish's transaction when this statement runs, so a quiet "unchanged"
     * here is what would leave a live version on a skill nobody can reach. The guard is the
     * {@code deleted_at IS NULL} in the statement plus the fact this test reads.
     */
    @Test
    void aPublishThatLostTheRowToADeleteCannotWriteTheProjection() {
        String draftId = submitDraft("v", null);
        String skillId = jdbc.sql("SELECT id FROM skill WHERE name = 'pdf-tools'")
                .query(String.class).optional().orElseThrow();
        send(request(path("/delete"), token).POST(HttpRequest.BodyPublishers.noBody()).build());

        boolean moved = metadata.recordPublish(skillId,
                new VersionProjection(
                        draftId, "1.0.0", "sha256:deadbeef", "2026-11-01T00:00:00Z",
                        "title", "description", "{}"),
                DraftProjection.empty(),
                "2026-11-01T00:00:00Z");

        assertThat(moved).as("the deleted row takes no projection").isFalse();
        assertThat(metadata.skillDeleted(skillId)).as("why it took none").isTrue();
        assertThat(jdbc.sql("SELECT current_version_id FROM skill WHERE id = :id")
                .param("id", skillId).query(String.class).optional())
                .as("the pointer did not move").isEmpty();
        assertThat(jdbc.sql("SELECT ever_published_at FROM skill WHERE id = :id")
                .param("id", skillId).query(String.class).optional())
                .as("the publish did not mark the skill as published").isEmpty();
    }

    /**
     * v37: the publish side of the race — a publish that loses the row to a delete throws, and the
     * transaction it ran in is rolled back, so no live version is left on a deleted skill.
     *
     * <p>The window is driven directly, because an HTTP test cannot pause between the access read and
     * the projection write. The delete goes first (a skill with only a draft may be deleted), then
     * the publish runs with the access it read a moment earlier.
     */
    @Test
    void aPublishThatLosesTheRowToADeleteRollsBack() {
        String draftId = submitDraft("v", null);
        Access access = jdbc.sql("""
                SELECT id, namespace_id, name, title, description,
                       frontmatter::text AS frontmatter, visibility, public_rung,
                       current_version_id, offline_at, ever_published_at, created_by
                  FROM skill WHERE name = 'pdf-tools'
                """).query((rs, rowNum) -> new Access(
                        rs.getString("id"), rs.getString("namespace_id"), rs.getString("name"),
                        rs.getString("title"), rs.getString("description"),
                        rs.getString("frontmatter"), rs.getString("visibility"),
                        rs.getString("public_rung"), null, rs.getString("current_version_id"),
                        rs.getString("offline_at"), true, null, rs.getString("created_by"),
                        rs.getString("ever_published_at") != null)).single();
        send(request(path("/delete"), token).POST(HttpRequest.BodyPublishers.noBody()).build());

        TransactionTemplate transaction = new TransactionTemplate(transactions);
        try {
            transaction.executeWithoutResult(status ->
                    versions.publishVersion(access, new VersionPin.RowId(draftId), null));
            org.junit.jupiter.api.Assertions.fail("the publish should have been refused");
        } catch (SkillDeletedException expected) {
            // The row's answer, and the reason the transaction below was rolled back.
        }

        assertThat(jdbc.sql("SELECT state FROM skill_version WHERE id = :id")
                .param("id", draftId).query(String.class).optional())
                .as("the version is still a draft: the publish rolled back").contains("draft");
        assertThat(jdbc.sql("SELECT first_published_at FROM skill_version WHERE id = :id")
                .param("id", draftId).query(String.class).optional()).isEmpty();
    }

    /** V31's backfill reads the first live version, replayed here from the shipped statement. */
    @Test
    void theV31BackfillFillsEverPublishedFromTheFirstLiveVersion() throws Exception {
        String draftId = submitDraft("v", null);
        publish(draftId, "1.0.0");
        jdbc.sql("UPDATE skill SET ever_published_at = NULL").update();

        String migration;
        try (var in = getClass().getResourceAsStream(
                "/db/migration/V31__skill_ever_published.sql")) {
            migration = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int start = migration.indexOf("UPDATE skill s");
        int end = migration.indexOf(";", start);
        jdbc.sql(migration.substring(start, end)).update();

        assertThat(jdbc.sql("SELECT ever_published_at FROM skill WHERE name = 'pdf-tools'")
                .query(String.class).optional())
                .as("the backfill found first_published_at").isPresent();
    }

    /** The three facts the CLI's precheck needs, on the one read it makes (v37). */
    @Test
    void theAuthorPageReportsOwnedOfflineAndEverPublished() {
        String draftId = submitDraft("v", null);
        JsonNode before = JSON.readTree(get(path("/versions"), token).body()).get("skill");
        assertThat(before.get("owned").asBoolean()).isTrue();
        assertThat(before.get("offline").asBoolean()).isFalse();
        assertThat(before.get("ever_published").asBoolean())
                .as("a draft has not gone live").isFalse();

        publish(draftId, "1.0.0");
        JsonNode after = JSON.readTree(get(path("/versions"), token).body()).get("skill");
        assertThat(after.get("ever_published").asBoolean()).isTrue();
    }

    @Test
    void theConsumptionListCarriesNoteStateAndStatusButNeverTheDraft() {
        String draftId = submitDraft("v1", "一句话说明");
        publish(draftId, "1.0.0");
        submitDraft("v2 draft", null);

        JsonNode body = JSON.readTree(get("/api/v1/skills/" + username
                + "/pdf-tools/versions", token).body());

        assertThat(body.get("versions")).hasSize(1);
        JsonNode row = body.get("versions").get(0);
        assertThat(row.get("note").asText()).isEqualTo("一句话说明");
        assertThat(row.get("state").asText()).isEqualTo("published");
        assertThat(row.get("status").asText()).isEqualTo("live");
        assertThat(body.toString()).doesNotContain("v2 draft");
    }

    /** T19–T27: the author list's filters and cursor, which the CLI's version commands read. */
    @Test
    void theVersionListFiltersByStatusAndQueryAndPagesWithACursor() {
        String published = submitDraft("published-name", "first note");
        String discarded = submitDraft("discarded-name", "second note");
        String waiting = submitDraft("waiting-name", "third note");
        publish(published, "1.0.0");
        discard(discarded);

        JsonNode drafts = JSON.readTree(get(path("/versions?status=draft"), token).body());
        assertThat(drafts.get("versions")).hasSize(1);
        assertThat(drafts.get("versions").get(0).get("code").asText()).isEqualTo(waiting);
        assertThat(drafts.get("counts").get("draft").asInt()).isEqualTo(1);

        JsonNode live = JSON.readTree(get(path("/versions?status=live"), token).body());
        assertThat(live.get("versions")).hasSize(1);
        assertThat(live.get("versions").get(0).get("code").asText()).isEqualTo(published);

        JsonNode discardedOnly = JSON.readTree(get(path("/versions?status=discarded"), token).body());
        assertThat(discardedOnly.get("versions")).hasSize(1);
        assertThat(discardedOnly.get("versions").get(0).get("code").asText()).isEqualTo(discarded);

        JsonNode queried = JSON.readTree(get(path("/versions?q=waiting-name"), token).body());
        assertThat(queried.get("versions")).hasSize(1);
        assertThat(queried.get("versions").get(0).get("code").asText()).isEqualTo(waiting);

        JsonNode pageOne = JSON.readTree(get(path("/versions?limit=2"), token).body());
        assertThat(pageOne.get("versions")).hasSize(2);
        String cursor = pageOne.get("next").asText();
        assertThat(cursor).isNotBlank();
        JsonNode pageTwo = JSON.readTree(
                get(path("/versions?limit=2&cursor=" + java.net.URLEncoder.encode(cursor,
                        java.nio.charset.StandardCharsets.UTF_8)), token).body());
        assertThat(pageTwo.get("versions")).hasSize(1);
        assertThat(pageTwo.get("next").isNull()).isTrue();

        java.util.Set<String> codes = new java.util.HashSet<>();
        pageOne.get("versions").forEach(row -> codes.add(row.get("code").asText()));
        pageTwo.get("versions").forEach(row -> codes.add(row.get("code").asText()));
        assertThat(codes).as("two pages, no repeats").containsExactlyInAnyOrder(
                published, discarded, waiting);
    }

    /** T38: a row published before version names existed is still a live, resolvable version. */
    @Test
    void anUpgradedPublishedRowWithoutAVersionNameStillListsAndResolves() {
        String draftId = submitDraft("old", null);
        publish(draftId, "1.0.0");
        // The upgrade state: published, current, and `version IS NULL` — exactly what V11 left
        // behind for rows written before authors declared numbers (ADR 0033 §后果).
        jdbc.sql("UPDATE skill_version SET version = NULL WHERE id = :id")
                .param("id", draftId)
                .update();

        JsonNode author = JSON.readTree(get(path("/versions"), token).body());
        assertThat(author.get("versions")).hasSize(1);
        JsonNode row = author.get("versions").get(0);
        assertThat(row.get("version").isNull()).isTrue();
        assertThat(row.get("status").asText()).isEqualTo("live");
        assertThat(row.get("code").asText()).isEqualTo(draftId);

        JsonNode consumption = JSON.readTree(get("/api/v1/skills/" + username
                + "/pdf-tools/versions", token).body());
        assertThat(consumption.get("versions")).hasSize(1);
        JsonNode served = consumption.get("versions").get(0);
        assertThat(served.get("name").isNull()).isTrue();
        assertThat(served.get("digest").asText()).startsWith("sha256:");

        // It is addressable by that digest, which is what a nameless version has.
        assertThat(get("/api/v1/skills/" + username + "/pdf-tools@"
                + served.get("digest").asText() + "/body", token).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private String path(String suffix) {
        return "/api/v1/author/skills/" + username + "/pdf-tools" + suffix;
    }

    private String submitDraft(String versionName, String note) {
        // A per-name file so two calls are two distinct contents: identical bytes are a replay of
        // the row already there (ADR 0005), which is a different case from a second draft.
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(Map.of(
                        "SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n",
                        "references/" + versionName + ".md", versionName)))
                .field("name", versionName);
        if (note != null) {
            multipart.field("note", note);
        }
        HttpResponse<String> response = send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
        assertThat(response.statusCode()).as("submit failed: %s", response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("version").get("draft_id").asText();
    }

    private void publish(String draftId, String releaseName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", draftId);
        body.put("release_name", releaseName);
        HttpResponse<String> response = webPost("/web/skills/" + username + "/pdf-tools/publish",
                json(body));
        assertThat(response.statusCode()).as("publish failed: %s", response.body()).isEqualTo(200);
    }

    private void discard(String draftId) {
        HttpResponse<String> response = send(request(path("/versions/discard"), token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("version", draftId))))
                .build());
        assertThat(response.statusCode()).as("discard failed: %s", response.body()).isEqualTo(200);
    }
}
