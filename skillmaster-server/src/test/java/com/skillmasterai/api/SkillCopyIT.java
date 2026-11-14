package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Browser;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * Copying a published version into one's own namespace (v54, ADR 0173).
 *
 * <p>The promise has five parts, and each test is one of them: the content is the source's version
 * (byte for byte under the same name, one rewritten field under a new one), the destination is a
 * private skill and a draft, the source must be readable at {@code browse} and not one's own,
 * provenance and license travel while the source's private facts do not, and a refusal leaves
 * nothing behind.
 *
 * <p>The source is published by the seeded official account: public visibility is official-only
 * (ADR 0146), and the private-shared case is the same skill with no visibility change and a grant.
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillCopyIT extends AbstractAccountIT {

    private static final String SKILLS = "/api/v1/skills";
    private static final String AUTHOR = "/api/v1/author/skills";

    private String owner;
    private String ownerToken;
    private String copier;
    private String copierToken;
    private Browser copierBrowser;

    @BeforeEach
    void twoAccounts() {
        String copierPhone = randomPhone();
        copier = randomUsername();
        String copierId = registerAndSignIn(copier, copierPhone);
        copierToken = tokenFor(copierId);
        copierBrowser = secondSignedInBrowser(copierPhone, PASSWORD);

        signInOfficialAccount();
        owner = OFFICIAL_HANDLE;
        ownerToken = tokenFor(OFFICIAL_USER_ID);
    }

    // ---------------------------------------------------------------------------------------
    // the successful copy
    // ---------------------------------------------------------------------------------------

    @Test
    void copiesTheLiveVersionIntoAPrivateSkillWithOneDraft() {
        String sourceVersion = published("source", "1.0.0", Map.of("references/a.md", "a\n"));
        makePublic("source", "browse");

        HttpResponse<String> copied = copy("source", Map.of("name", "source-copy"));

        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);
        JsonNode answer = body(copied);
        assertThat(answer.get("skill").get("namespace").asText()).isEqualTo(copier);
        assertThat(answer.get("skill").get("name").asText()).isEqualTo("source-copy");
        assertThat(answer.get("draft").asText()).isNotEmpty();
        assertThat(answer.get("draft_name").asText()).isEqualTo("复制自 " + owner + "/source");
        assertThat(answer.get("version").isNull()).as("a draft has no version number yet").isTrue();
        assertThat(answer.get("source").get("address").asText()).isEqualTo(owner + "/source");
        assertThat(answer.get("source").get("version").asText()).isEqualTo("1.0.0");

        assertThat(count("SELECT COUNT(*) FROM skill WHERE name = 'source-copy'"
                + " AND visibility = 'private'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM skill_version WHERE skill_id ="
                + " (SELECT id FROM skill WHERE name = 'source-copy')"
                + " AND state = 'draft'")).isEqualTo(1);

        assertManifestMatches(answer.get("draft").asText(), sourceVersion);
    }

    @Test
    void copiesAPublishedHistoryVersionWhenTheAddressPinsOne() {
        published("source", "1.0.0", Map.of());
        String liveVersion = published("source", "1.1.0", Map.of());
        makePublic("source", "browse");

        HttpResponse<String> copied = copy("source", Map.of("name", "old", "version", "1.0.0"));

        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);
        String draft = body(copied).get("draft").asText();
        String copiedBody = draftFile("old", draft, "SKILL.md");
        String historical = fileAt(owner, "source", "1.0.0", "SKILL.md");
        String live = fileAt(owner, "source", liveVersion, "SKILL.md");
        // The body is the historical one under the new name: same text after the one rewritten
        // field, and not the live version's text.
        assertThat(historical).contains("body of source at 1.0.0");
        assertThat(live).contains("body of source at 1.1.0");
        assertThat(copiedBody).contains("body of source at 1.0.0").contains("name: old");
        assertThat(copiedBody).doesNotContain("at 1.1.0");
    }

    @Test
    void aSharedPrivateSkillCanBeCopied() {
        published("source", "1.0.0", Map.of());
        materializeGrant(owner, "source", copier, "browse");

        HttpResponse<String> copied = copy("source", Map.of("name", "shared-copy"));

        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);
        assertThat(body(copied).get("source").get("version").asText()).isEqualTo("1.0.0");
        String draft = body(copied).get("draft").asText();
        assertThat(draftFile("shared-copy", draft, "SKILL.md"))
                .as("the private source's content, renamed to the new address")
                .contains("name: shared-copy").contains("body of source at 1.0.0");
        // A copy whose source declared no license still carries its provenance in the exported zip.
        String attribution = new String(
                unzip(archive("shared-copy", draft)).get(".skillmaster/attribution.md"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(attribution).contains("复制自：" + owner + "/source @1.0.0")
                .contains("许可：");
    }

    @Test
    void theSameNameInAnotherNamespaceIsAllowed() {
        published("source", "1.0.0", Map.of());
        makePublic("source", "browse");

        HttpResponse<String> copied = copy("source", Map.of("name", "source"));

        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);
        assertThat(body(copied).get("skill").get("namespace").asText()).isEqualTo(copier);
        assertThat(body(copied).get("skill").get("name").asText()).isEqualTo("source");
        assertThat(count("SELECT COUNT(*) FROM skill WHERE name = 'source'")).isEqualTo(2);
    }

    @Test
    void renamingRewritesOnlyTheSkillMdName() {
        published("source", "1.0.0", Map.of("references/a.md", "a\n"));
        makePublic("source", "browse");

        HttpResponse<String> copied = copy("source", Map.of("name", "source-copy"));

        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);
        String draft = body(copied).get("draft").asText();
        String skillMd = draftFile("source-copy", draft, "SKILL.md");
        assertThat(skillMd).contains("name: source-copy").contains("body of source at 1.0.0");
        assertThat(draftFile("source-copy", draft, "references/a.md")).isEqualTo("a\n");
        assertThat(fileAt(owner, "source", "1.0.0", "SKILL.md"))
                .as("the source version is untouched")
                .contains("name: source");
    }

    @Test
    void theSameNameCopiesTheBytesUnchanged() {
        String sourceVersion = published("source", "1.0.0", Map.of("references/a.md", "a\n"));
        makePublic("source", "browse");

        HttpResponse<String> copied = copy("source", Map.of("name", "source"));

        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);
        String draft = body(copied).get("draft").asText();
        assertThat(draftDigest("source", draft))
                .as("an unchanged manifest has the source's digest")
                .isEqualTo(sourceDigest("source", sourceVersion));
        assertThat(draftFile("source", draft, "SKILL.md"))
                .isEqualTo(fileAt(owner, "source", "1.0.0", "SKILL.md"));
    }

    @Test
    void provenanceAndLicenseTravel() {
        String sourceVersion = published("source", "1.0.0", Map.of());
        jdbc.sql("INSERT INTO skill_version_license (version_id, spdx, name, copyright, status,"
                        + " license_text, source_path, source_url, detail, fetched_at)"
                        + " SELECT id, 'MIT', 'MIT License', 'Copyright somebody', 'ok', 'text',"
                        + " 'LICENSE', 'https://example.test/license', '{}',"
                        + " '2026-01-01T00:00:00Z' FROM skill_version WHERE id = :id"
                        + " ON CONFLICT (version_id) DO UPDATE SET spdx = EXCLUDED.spdx,"
                        + " name = EXCLUDED.name, copyright = EXCLUDED.copyright,"
                        + " status = EXCLUDED.status, license_text = EXCLUDED.license_text,"
                        + " source_path = EXCLUDED.source_path, source_url = EXCLUDED.source_url,"
                        + " detail = EXCLUDED.detail, fetched_at = EXCLUDED.fetched_at")
                .param("id", versionId("source", sourceVersion))
                .update();
        makePublic("source", "browse");

        HttpResponse<String> copied = copy("source", Map.of("name", "licensed"));
        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);

        // The copy is a draft-only skill, so the consumption plane has no metadata for it yet;
        // the author page is where the information page reads provenance from.
        HttpResponse<String> metaAnswer = copierBrowser.get(
                uri("/web/skills/" + copier + "/licensed"));
        assertThat(metaAnswer.statusCode()).as(metaAnswer.body()).isEqualTo(200);
        JsonNode metadata = JSON.readTree(metaAnswer.body());
        assertThat(metadata.get("copied_from")).as(metaAnswer.body()).isNotNull();
        assertThat(metadata.get("copied_from").get("address").asText())
                .isEqualTo(owner + "/source");
        assertThat(metadata.get("copied_from").get("version").asText()).isEqualTo("1.0.0");
        assertThat(metadata.get("license").get("spdx").asText()).isEqualTo("MIT");
        assertThat(metadata.get("license").get("name").asText()).isEqualTo("MIT License");

        assertThat(count("SELECT COUNT(*) FROM skill_version_license WHERE version_id ="
                + " (SELECT id FROM skill_version WHERE skill_id = (SELECT id FROM skill"
                + " WHERE name = 'licensed'))")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM skill_copy WHERE source_skill_id ="
                + " (SELECT id FROM skill WHERE name = 'source')")).isEqualTo(1);
        // Both halves of the source identity are the source's own rows, not just a matching address.
        assertThat(jdbc.sql("SELECT c.source_skill_id FROM skill_copy c JOIN skill s"
                        + " ON s.id = c.skill_id WHERE s.name = 'licensed'")
                .query(String.class).single())
                .isEqualTo(jdbc.sql("SELECT id FROM skill WHERE name = 'source'")
                        .query(String.class).single());
        assertThat(jdbc.sql("SELECT c.source_version_id FROM skill_copy c JOIN skill s"
                        + " ON s.id = c.skill_id WHERE s.name = 'licensed'")
                .query(String.class).single()).isEqualTo(versionId("source", sourceVersion));
        assertThat(jdbc.sql("SELECT copied_at FROM skill_copy c JOIN skill s"
                        + " ON s.id = c.skill_id WHERE s.name = 'licensed'")
                .query(String.class).single()).isNotBlank();

        String draft = body(copied).get("draft").asText();
        String attribution = new String(
                unzip(archive("licensed", draft)).get(".skillmaster/attribution.md"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(attribution).contains("复制自：" + owner + "/source @1.0.0")
                .contains("许可：MIT");
    }

    @Test
    void aSourceThatDeclaredNoLicenseIsCopiedAsUndeclared() {
        String sourceVersion = published("source", "1.0.0", Map.of());
        makePublic("source", "browse");
        // The publish path already recorded "no license" as a `missing` row; the copy must carry
        // that conclusion, not promote it to a usable license.
        assertThat(jdbc.sql("SELECT status FROM skill_version_license WHERE version_id = :id")
                .param("id", versionId("source", sourceVersion))
                .query(String.class).single()).isEqualTo("missing");

        HttpResponse<String> copied = copy("source", Map.of("name", "undeclared"));
        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);

        String status = jdbc.sql("SELECT status FROM skill_version_license WHERE version_id ="
                        + " (SELECT id FROM skill_version WHERE skill_id = (SELECT id FROM skill"
                        + " WHERE name = 'undeclared'))")
                .query(String.class).single();
        assertThat(status).isEqualTo("missing");

        // The exported notice says it in the reader's word, not SPDX's sentinel (PRD A6).
        String draft = body(copied).get("draft").asText();
        String attribution = new String(
                unzip(archive("undeclared", draft)).get(".skillmaster/attribution.md"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(attribution).contains("复制自：" + owner + "/source @1.0.0")
                .contains("许可：未声明")
                .doesNotContain("NOASSERTION");
    }

    @Test
    void theWebRouteCopiesTheSameWay() {
        published("source", "1.0.0", Map.of());
        makePublic("source", "browse");

        HttpResponse<String> copied = copierBrowser.post(
                uri("/web/skills/" + owner + "/source/copy"), json(Map.of("name", "web-copy")));

        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);
        assertThat(body(copied).get("skill").get("namespace").asText()).isEqualTo(copier);
        assertThat(count("SELECT COUNT(*) FROM skill WHERE name = 'web-copy'")).isEqualTo(1);
    }

    @Test
    void privateFactsDoNotTravel() {
        published("source", "1.0.0", Map.of());
        jdbc.sql("UPDATE skill SET category_key = 'office-efficiency' WHERE name = 'source'").update();
        // Usage is a fact about the source, not about its content.
        jdbc.sql("INSERT INTO skill_usage_total (skill_id, scope, calls)"
                + " SELECT id, 'public', 7 FROM skill WHERE name = 'source'").update();
        jdbc.sql("INSERT INTO skill_usage_day (skill_id, day, account_id, scope)"
                + " SELECT s.id, '2026-01-01', u.id, 'public' FROM skill s, app_user u"
                + " WHERE s.name = 'source' LIMIT 1").update();
        materializeGrant(owner, "source", copier, "browse");

        HttpResponse<String> copied = copy("source", Map.of("name", "bare"));

        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);
        assertThat(count("SELECT COUNT(*) FROM skill WHERE name = 'bare' AND category_key IS NULL"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM skill_grant WHERE skill_id ="
                + " (SELECT id FROM skill WHERE name = 'bare')")).isZero();
        assertThat(count("SELECT COUNT(*) FROM skill WHERE name = 'bare'"
                + " AND visibility = 'private'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM skill_usage_total WHERE skill_id ="
                + " (SELECT id FROM skill WHERE name = 'bare')")).isZero();
        assertThat(count("SELECT COUNT(*) FROM skill_usage_day WHERE skill_id ="
                + " (SELECT id FROM skill WHERE name = 'bare')")).isZero();
    }

    @Test
    void theCopyIsAudited() {
        published("source", "1.0.0", Map.of());
        makePublic("source", "browse");

        HttpResponse<String> copied = copy("source", Map.of("name", "audited"));
        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);

        assertThat(count("SELECT COUNT(*) FROM audit_event WHERE action = 'copy'"
                + " AND target_type = 'skill'")).isEqualTo(1);
        String detail = jdbc.sql("SELECT detail FROM audit_event WHERE action = 'copy'")
                .query(String.class).single();
        assertThat(detail).contains("source_address").contains("source_version")
                .contains("audited")
                // The draft id and the created flag are what a follow-up query joins on.
                .contains("\"draft\"").contains("\"created\":true");
    }

    // ---------------------------------------------------------------------------------------
    // refusals
    // ---------------------------------------------------------------------------------------

    @Test
    void aDraftOrDiscardedVersionCannotBeCopied() {
        published("source", "1.0.0", Map.of());
        makePublic("source", "browse");
        String draft = draft("source", "2.0.0");
        HttpResponse<String> fromDraft =
                copy("source", Map.of("name", "from-draft", "version", draft));
        assertThat(fromDraft.statusCode()).isEqualTo(404);
        assertThat(body(fromDraft).get("error").get("code").asText())
                .isEqualTo("skill_not_found");
        HttpResponse<String> discarded = webPost("/web/skills/" + owner + "/source/discard",
                json(Map.of("version", draft)));
        assertThat(discarded.statusCode()).as(discarded.body()).isEqualTo(200);
        assertThat(copy("source", Map.of("name", "from-draft", "version", draft)).statusCode())
                .isEqualTo(404);
        assertThat(count("SELECT COUNT(*) FROM skill WHERE name LIKE 'from-draft'")).isZero();
    }

    @Test
    void aRevokedVersionCannotBeCopied() {
        published("source", "1.0.0", Map.of());
        published("source", "1.1.0", Map.of());
        makePublic("source", "browse");
        assertThat(webPost("/web/skills/" + owner + "/source/versions/1.0.0/revoke", "{}")
                .statusCode()).isEqualTo(200);

        HttpResponse<String> refused = copy("source", Map.of("name", "gone", "version", "1.0.0"));

        assertThat(refused.statusCode()).isEqualTo(410);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("version_revoked");
        assertThat(count("SELECT COUNT(*) FROM skill WHERE name = 'gone'")).isZero();
    }

    @Test
    void theOwnerCannotCopyTheirOwnSkill() {
        published("source", "1.0.0", Map.of());
        makePublic("source", "browse");

        HttpResponse<String> refused = send(request(
                AUTHOR + "/" + owner + "/source/copy", ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        json(Map.of("name", "mine-copy")))).build());

        assertThat(refused.statusCode()).isEqualTo(403);
        JsonNode error = body(refused).get("error");
        assertThat(error.get("code").asText()).isEqualTo("forbidden");
        assertThat(error.get("details").toString()).contains("owner");
    }

    @Test
    void invokeOnlyCannotCopy() {
        published("source", "1.0.0", Map.of());
        materializeGrant(owner, "source", copier, "invoke");

        HttpResponse<String> refused = copy("source", Map.of("name", "no-browse"));

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(body(refused).get("error").get("details").toString())
                .contains("browse_required");
    }

    @Test
    void anInvisibleSourceIsANotFound() {
        published("source", "1.0.0", Map.of());

        assertThat(copy("source", Map.of("name", "nope")).statusCode()).isEqualTo(404);
        assertThat(count("SELECT COUNT(*) FROM skill WHERE name = 'nope'")).isZero();
    }

    @Test
    void aSkillWithNothingPublishedCannotBeCopied() {
        draft("source", "0.1.0");
        makePublic("source", "browse");

        assertThat(copy("source", Map.of("name", "empty")).statusCode()).isEqualTo(404);
    }

    @Test
    void anOfflineSourceIsNotServed() {
        published("source", "1.0.0", Map.of());
        makePublic("source", "browse");
        assertThat(webPost("/web/skills/" + owner + "/source/offline", "").statusCode())
                .isEqualTo(200);

        HttpResponse<String> refused = copy("source", Map.of("name", "offline-copy"));

        assertThat(refused.statusCode()).isEqualTo(503);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("skill_offline");
    }

    @Test
    void anExistingNameIsRefusedWithZeroWrites() {
        published("source", "1.0.0", Map.of());
        makePublic("source", "browse");
        assertThat(createSkill("source-copy").statusCode()).isEqualTo(201);
        long[] before = rows();

        HttpResponse<String> refused = copy("source", Map.of("name", "source-copy"));

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(body(refused).get("error").get("code").asText())
                .isEqualTo("skill_already_exists");
        assertThat(rows()).isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM skill_copy")).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_event WHERE action = 'copy'")).isZero();
    }

    @Test
    void aDeletedNameIsFreeAgain() {
        published("source", "1.0.0", Map.of());
        makePublic("source", "browse");
        assertThat(createSkill("source-copy").statusCode()).isEqualTo(201);
        assertThat(send(request(AUTHOR + "/" + copier + "/source-copy/delete", copierToken)
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build()).statusCode())
                .isEqualTo(204);

        // v61 (ADR 0183): the copy's target name was released by the delete.
        HttpResponse<String> created = copy("source", Map.of("name", "source-copy"));

        assertThat(created.statusCode()).as("copy: %s", created.body()).isEqualTo(201);
        assertThat(count("SELECT COUNT(*) FROM skill_copy")).isEqualTo(1);
    }

    @Test
    void anInvalidOrReservedTargetNameIsUnprocessable() {
        published("source", "1.0.0", Map.of());

        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("Bad_Name", "invalid_name");
        cases.put("a--b", "invalid_name");
        cases.put("skillmaster-x", "name_reserved");
        cases.put("x".repeat(65), "name_too_long");

        for (Map.Entry<String, String> entry : cases.entrySet()) {
            HttpResponse<String> refused = copy("source", Map.of("name", entry.getKey()));
            assertThat(refused.statusCode()).as(entry.getKey() + ": " + refused.body())
                    .isEqualTo(422);
            assertThat(body(refused).get("error").get("code").asText()).isEqualTo("invalid_name");
            assertThat(body(refused).get("error").get("details").toString())
                    .contains(entry.getValue());
        }
        assertThat(count("SELECT COUNT(*) FROM skill WHERE name NOT IN ('source')")).isZero();
    }

    @Test
    void aSourceWhoseNameCannotBeRewrittenIsRefused() {
        // A flow mapping is valid YAML and a valid skill, but it has no top-level `name:` line to
        // rewrite in place. The copy refuses instead of re-serialising the frontmatter — a rewritten
        // document would no longer be the author's bytes (ADR 0005), and a guessed one could change
        // more than the name.
        Multipart multipart = Multipart.create()
                .file("file", "flow.zip", Zips.ofText(Map.of("flow/SKILL.md",
                        "---\n{name: flow, description: a copy source}\n---\nbody of flow\n")));
        HttpResponse<String> submitted = send(request(SKILLS, ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher()).build());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(201);
        String draft = JSON.readTree(submitted.body()).get("version").get("draft_id").asText();
        assertThat(webPost("/web/skills/" + owner + "/flow/publish", json(Map.of(
                "version", draft, "release_name", "1.0.0"))).statusCode())
                .as("publish failed").isEqualTo(200);
        makePublic("flow", "browse");

        HttpResponse<String> renamed = copy("flow", Map.of("name", "flow-copy"));

        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(409);
        assertThat(body(renamed).get("error").get("code").asText())
                .isEqualTo("name_not_rewritable");
        assertThat(count("SELECT COUNT(*) FROM skill WHERE name = 'flow-copy'")).isZero();

        // Under its own name nothing is rewritten, so the same source copies.
        HttpResponse<String> same = copy("flow", Map.of("name", "flow"));
        assertThat(same.statusCode()).as(same.body()).isEqualTo(201);
    }

    @Test
    void aVersionWithoutANameIsLabelledByItsDigest() {
        String sourceVersion = published("source", "1.0.0", Map.of());
        makePublic("source", "browse");
        jdbc.sql("UPDATE skill_version SET version = NULL WHERE id = :id")
                .param("id", versionId("source", sourceVersion)).update();

        HttpResponse<String> copied = copy("source", Map.of("name", "nameless"));

        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);
        assertThat(body(copied).get("source").get("version").asText()).startsWith("sha256:");
        String detail = jdbc.sql("SELECT source_version_label FROM skill_copy"
                + " WHERE skill_id = (SELECT id FROM skill WHERE name = 'nameless')")
                .query(String.class).single();
        assertThat(detail).startsWith("sha256:");
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    /** Submits and publishes one version; returns the version name it was published as. */
    private String published(String name, String releaseName, Map<String, String> extra) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(name + "/SKILL.md", "---\nname: " + name + "\ndescription: a copy source\n"
                + "---\nbody of " + name + " at " + releaseName + "\n");
        extra.forEach((path, text) -> files.put(name + "/" + path, text));
        Multipart multipart = Multipart.create()
                .file("file", name + ".zip", Zips.ofText(files));
        HttpResponse<String> submitted = send(request(SKILLS, ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher()).build());
        assertThat(submitted.statusCode()).as("submit failed: %s", submitted.body()).isEqualTo(201);
        String draft = JSON.readTree(submitted.body()).get("version").get("draft_id").asText();
        assertThat(webPost("/web/skills/" + owner + "/" + name + "/publish", json(Map.of(
                "version", draft, "release_name", releaseName))).statusCode())
                .as("publish failed").isEqualTo(200);
        return releaseName;
    }

    /** A draft with different content from any published version. */
    private String draft(String name, String releaseName) {
        Map<String, String> files = Map.of(name + "/SKILL.md",
                "---\nname: " + name + "\ndescription: a copy source\n---\ndraft " + releaseName
                        + "\n");
        Multipart multipart = Multipart.create()
                .file("file", name + ".zip", Zips.ofText(files));
        HttpResponse<String> submitted = send(request(SKILLS, ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher()).build());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(201);
        return JSON.readTree(submitted.body()).get("version").get("draft_id").asText();
    }

    private void makePublic(String name, String rung) {
        HttpResponse<String> changed = browser.patch(
                uri("/web/skills/" + owner + "/" + name + "/visibility"),
                json(Map.of("visibility", "public", "public_rung", rung)));
        assertThat(changed.statusCode()).as("visibility: %s", changed.body()).isEqualTo(204);
    }

    private HttpResponse<String> copy(String sourceName, Map<String, ?> body) {
        return send(request(AUTHOR + "/" + owner + "/" + sourceName + "/copy", copierToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json(body))).build());
    }

    private HttpResponse<String> createSkill(String name) {
        return send(request(AUTHOR, copierToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json(Map.of(
                        "name", name, "description", "already mine", "version_name", "第一版"))))
                .build());
    }

    private byte[] archive(String skillName, String draft) {
        HttpResponse<byte[]> response = sendBytes(request(
                AUTHOR + "/" + copier + "/" + skillName + "/versions/" + draft + "/archive",
                copierToken).GET().build());
        assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
        return response.body();
    }

    private static Map<String, byte[]> unzip(byte[] zip) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream in =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (java.util.zip.ZipEntry entry = in.getNextEntry(); entry != null;
                    entry = in.getNextEntry()) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        return entries;
    }

    private String draftFile(String skillName, String draft, String relpath) {
        HttpResponse<String> response = send(request(
                AUTHOR + "/" + copier + "/" + skillName + "/versions/" + draft + "/files/"
                        + relpath, copierToken).GET().build());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    private String fileAt(String namespace, String skillName, String version, String relpath) {
        HttpResponse<byte[]> response = getBytes(SKILLS + "/" + namespace + "/" + skillName + "@"
                + version + "/files/" + relpath, copierToken);
        assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
        return new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String sourceDigest(String skillName, String version) {
        JsonNode row = body(get(SKILLS + "/" + owner + "/" + skillName + "@" + version,
                copierToken)).get("version");
        return row.get("digest").asText();
    }

    private String draftDigest(String skillName, String draft) {
        JsonNode row = body(get(AUTHOR + "/" + copier + "/" + skillName + "/versions/" + draft,
                copierToken)).get("version");
        return row.get("digest").asText();
    }

    private String versionId(String skillName, String version) {
        return jdbc.sql("SELECT v.id FROM skill_version v JOIN skill s ON s.id = v.skill_id"
                        + " WHERE s.name = :name AND v.version = :version")
                .param("name", skillName).param("version", version)
                .query(String.class).single();
    }

    private void assertManifestMatches(String draft, String sourceVersion) {
        JsonNode copiedFiles = body(get(AUTHOR + "/" + copier + "/source-copy/versions/" + draft
                + "/files", copierToken)).get("files");
        JsonNode sourceFiles = body(get(SKILLS + "/" + owner + "/source@" + sourceVersion,
                copierToken)).get("files");
        assertThat(copiedFiles).hasSameSizeAs(sourceFiles);
        for (int index = 0; index < sourceFiles.size(); index++) {
            String relpath = sourceFiles.get(index).get("relpath").asText();
            assertThat(copiedFiles.get(index).get("relpath").asText()).isEqualTo(relpath);
            // The root SKILL.md is the one file a renamed copy rewrites; every other path shares
            // the source's blob, digest and size.
            if ("SKILL.md".equals(relpath)) {
                continue;
            }
            assertThat(copiedFiles.get(index).get("sha256").asText())
                    .isEqualTo(sourceFiles.get(index).get("sha256").asText());
            assertThat(copiedFiles.get(index).get("size").asLong())
                    .isEqualTo(sourceFiles.get(index).get("size").asLong());
        }
    }

    private long[] rows() {
        return new long[] {
            count("SELECT COUNT(*) FROM skill"),
            count("SELECT COUNT(*) FROM skill_version"),
            count("SELECT COUNT(*) FROM version_file"),
        };
    }
}
