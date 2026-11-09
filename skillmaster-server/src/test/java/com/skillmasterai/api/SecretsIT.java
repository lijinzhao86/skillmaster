package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.support.Browser;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The vault's whole path: create → bind → lease → delete, plus the metadata flag the CLI branches
 * on. Runs against the real HTTP stack and the real PostgreSQL test database.
 */
@Sql("/sql/truncate-business-tables.sql")
class SecretsIT extends AbstractOrganizeIT {

    @Test
    void aValueIsCreatedBoundLeasedAndDeleted() {
        publicSkill("vault");
        publicSkill("vault-two");
        String token = token();

        HttpResponse<String> create = postJson("/api/v1/secrets", token, json(Map.of(
                "name", "cookie",
                "value", "test-cookie-value",
                "skill", "demo/vault",
                "env", "COOKIE")));
        assertThat(create.statusCode()).as(create.body()).isEqualTo(201);
        JsonNode created = json(create);
        String id = created.get("id").asText();
        assertThat(created.get("name").asText()).isEqualTo("cookie");
        assertThat(created.get("per_day").asInt()).isEqualTo(500);
        assertThat(created.get("used").asInt()).isZero();
        assertThat(created.get("bindings").get(0).get("skill").asText()).isEqualTo("demo/vault");
        assertThat(created.get("bindings").get(0).get("env").asText()).isEqualTo("COOKIE");
        assertThat(create.body()).doesNotContain("test-cookie-value");

        HttpResponse<String> list = get("/api/v1/secrets", token);
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(list.body()).doesNotContain("test-cookie-value");
        assertThat(json(list).get("items").get(0).get("id").asText()).isEqualTo(id);

        HttpResponse<String> bound = postJson("/api/v1/secrets/" + id + "/bindings", token,
                json(Map.of("skill", "demo/vault-two", "env", "OTHER")));
        assertThat(bound.statusCode()).as(bound.body()).isEqualTo(201);

        HttpResponse<String> conflict = postJson("/api/v1/secrets/" + id + "/bindings", token,
                json(Map.of("skill", "demo/vault-two", "env", "OTHER")));
        assertThat(conflict.statusCode()).as(conflict.body()).isEqualTo(409);
        assertThat(json(conflict).get("error").get("code").asText())
                .isEqualTo("credential_binding_conflict");

        HttpResponse<String> lease = post("/api/v1/skills/demo/vault/credentials/lease", token);
        assertThat(lease.statusCode()).as(lease.body()).isEqualTo(200);
        assertThat(lease.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        JsonNode values = json(lease).get("values");
        assertThat(values).hasSize(1);
        assertThat(values.get(0).get("env").asText()).isEqualTo("COOKIE");
        assertThat(values.get(0).get("value").asText()).isEqualTo("test-cookie-value");
        assertThat(count("SELECT count(*) FROM credential_use WHERE credential_id = :id",
                Map.of("id", id))).isEqualTo(1);

        HttpResponse<String> deleted = delete("/api/v1/secrets/" + id, token);
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM service_credential WHERE id = :id",
                Map.of("id", id))).isZero();
        assertThat(count("SELECT count(*) FROM credential_binding WHERE credential_id = :id",
                Map.of("id", id))).isZero();

        HttpResponse<String> after = post("/api/v1/skills/demo/vault/credentials/lease", token);
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(json(after).get("values")).isEmpty();
    }

    @Test
    void metadataSaysWhetherThisCallerWouldReceiveAValue() {
        // The metadata read resolves a version, so this fixture has to be a real published row —
        // the list fixture's bare skill is enough for the binding paths but not for this one.
        publishedSkill("vault");
        String token = token();

        JsonNode before = json(get("/api/v1/skills/demo/vault/metadata", token));
        assertThat(before.get("credentials_bound").asBoolean()).isFalse();

        postJson("/api/v1/secrets", token, json(Map.of(
                "name", "cookie",
                "value", "test-cookie-value",
                "skill", "demo/vault",
                "env", "COOKIE")));

        JsonNode after = json(get("/api/v1/skills/demo/vault/metadata", token));
        assertThat(after.get("credentials_bound").asBoolean()).isTrue();
    }

    /**
     * The console's plane walks the same lifecycle as the CLI's, with a session instead of a key.
     *
     * <p>It is a second mapping rather than a second implementation: one wrong wiring here (a missing
     * CSRF requirement, a response that echoes the value) would be invisible from the API plane's
     * tests, because that plane cannot see this one at all.
     */
    @Test
    void theConsolePlaneWalksTheSameLifecycle() {
        publishedSkill("vault-web");
        publishedSkill("vault-web-two");
        // Signing in is what puts a session in the jar; until then a write is refused as anonymous,
        // which would be the 401 this assertion is not about.
        token();

        // A write without the CSRF header is refused before anything else happens.
        assertThat(webPost("/web/secrets", json(Map.of(
                "name", "cookie", "value", "test-cookie-value",
                "skill", "demo/vault-web", "env", "COOKIE")), null).statusCode()).isEqualTo(403);

        HttpResponse<String> create = webPost("/web/secrets", json(Map.of(
                "name", "cookie", "value", "test-cookie-value",
                "skill", "demo/vault-web", "env", "COOKIE")), csrfToken());
        assertThat(create.statusCode()).as(create.body()).isEqualTo(201);
        assertThat(create.body()).doesNotContain("test-cookie-value");
        String id = json(create).get("id").asText();

        HttpResponse<String> listed = webGet("/web/secrets");
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(json(listed).get("items")).hasSize(1);
        assertThat(listed.body()).doesNotContain("test-cookie-value");

        assertThat(webPost("/web/secrets/" + id + "/bindings",
                json(Map.of("skill", "demo/vault-web-two", "env", "TOKEN")), csrfToken())
                .statusCode()).isEqualTo(201);
        assertThat(browser.patch(uri("/web/secrets/" + id), json(Map.of("per_day", 900)))
                .statusCode()).isEqualTo(200);
        // PUT is the one method the jar has no convenience for; it still carries the session.
        assertThat(browser.request(HttpRequest.newBuilder(uri("/web/secrets/" + id + "/value"))
                .header("X-XSRF-TOKEN", csrfToken())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json(
                        Map.of("value", "a-new-cookie-value")))))
                .statusCode()).isEqualTo(200);

        assertThat(browser.delete(uri("/web/secrets/" + id + "/bindings?skill=demo/vault-web-two"
                + "&env=TOKEN")).statusCode()).isEqualTo(204);
        assertThat(browser.delete(uri("/web/secrets/" + id)).statusCode()).isEqualTo(204);
        assertThat(json(webGet("/web/secrets")).get("items")).isEmpty();
    }

    /**
     * **A share is a grant on (credential, skill), not on the rows that existed when it was made.**
     * A variable bound later is part of the same grant; a collaboration that ends ends it too.
     *
     * <p><strong>And it can only be created from a session</strong> (v52 P2): the API plane answers
     * 405 on the same path, so the console is the one place a grant starts. The owner here is a
     * freshly registered account rather than the seeded one, because the test needs the owner's
     * <em>browser</em> after a collaborator has been registered — and registering replaces the jar
     * ({@code tokenFor}'s guard says so). A second browser signed in with the password the test
     * registered is the way in.
     */
    @Test
    void aShareIsCreatedOnTheWebPlaneAndFollowsTheSkill() {
        publicSkill("vault-share");
        String owner = randomUsername();
        String ownerPhone = randomPhone();
        String ownerId = registerAndSignIn(owner, ownerPhone);
        String ownerToken = tokenFor(ownerId);

        HttpResponse<String> create = postJson("/api/v1/secrets", ownerToken, json(Map.of(
                "name", "cookie",
                "value", "test-cookie-value",
                "skill", "demo/vault-share",
                "env", "COOKIE")));
        assertThat(create.statusCode()).as(create.body()).isEqualTo(201);
        String id = json(create).get("id").asText();

        String collaborator = randomUsername();
        String collaboratorToken = tokenFor(registerAndSignIn(collaborator, randomPhone()));
        materializeGrant("demo", "vault-share", collaborator, "browse");

        String shareBody = json(Map.of("skill", "demo/vault-share", "user", collaborator));

        // The API plane has no way to create a share any more: 405, not 404 — the path is there and
        // the method is what moved.
        HttpResponse<String> onApi = postJson("/api/v1/secrets/" + id + "/shares", ownerToken,
                shareBody);
        assertThat(onApi.statusCode()).as(onApi.body()).isEqualTo(405);

        // The collaborator's own session cannot create one either — only the credential's owner.
        assertThat(webPost("/web/secrets/" + id + "/shares", shareBody, csrfToken()).statusCode())
                .as("a collaborator creating a share").isEqualTo(404);

        Browser ownerBrowser = secondSignedInBrowser(ownerPhone, PASSWORD);
        HttpResponse<String> shared = ownerBrowser.post(uri("/web/secrets/" + id + "/shares"),
                shareBody, ownerBrowser.csrfToken());
        assertThat(shared.statusCode()).as(shared.body()).isEqualTo(201);

        assertThat(json(post("/api/v1/skills/demo/vault-share/credentials/lease",
                collaboratorToken)).get("values")).hasSize(1);

        // The collaborator cannot expose somebody else's value; only its owner binds it.
        HttpResponse<String> borrowed = postJson("/api/v1/secrets/" + id + "/bindings",
                collaboratorToken, json(Map.of("skill", "demo/vault-share", "env", "TOKEN")));
        assertThat(borrowed.statusCode()).as(borrowed.body()).isEqualTo(404);

        // The grantee can see what they receive — and who lent it — which is what the skill pane's
        // 「共享自 …」 row is drawn from.
        JsonNode received = json(get("/api/v1/secrets/incoming?skill=demo/vault-share",
                collaboratorToken));
        assertThat(received.get("items")).as(received.toString()).hasSize(1);
        assertThat(received.get("items").get(0).get("env").asText()).isEqualTo("COOKIE");
        assertThat(received.get("items").get(0).get("owner").asText()).isEqualTo(owner);
        assertThat(received.toString()).doesNotContain("test-cookie-value");

        // A second variable on the same skill is covered by the grant that already exists.
        assertThat(postJson("/api/v1/secrets/" + id + "/bindings", ownerToken,
                json(Map.of("skill", "demo/vault-share", "env", "TOKEN"))).statusCode())
                .isEqualTo(201);
        assertThat(json(post("/api/v1/skills/demo/vault-share/credentials/lease",
                collaboratorToken)).get("values")).hasSize(2);
        assertThat(json(get("/api/v1/secrets/incoming?skill=demo/vault-share", collaboratorToken))
                .get("items")).hasSize(2);

        // Withdrawing the collaboration ends the share: the credential must not outlive it.
        jdbc.sql("""
                DELETE FROM skill_grant g
                 USING skill s, namespace n
                 WHERE g.skill_id = s.id AND s.namespace_id = n.id
                   AND n.slug = 'demo' AND s.name = 'vault-share'
                   AND g.grantee_id = (SELECT id FROM app_user WHERE handle = :handle)
                """)
                .param("handle", collaborator)
                .update();
        assertThat(json(post("/api/v1/skills/demo/vault-share/credentials/lease",
                collaboratorToken)).get("values")).isEmpty();
        // **And the listing has to agree with the lease.** A grant that no longer reaches the caller
        // must not keep naming the owner's credential in the skill pane — that pane exists to
        // explain why the skill runs, and after this point it would explain it wrongly.
        assertThat(json(get("/api/v1/secrets/incoming?skill=demo/vault-share", collaboratorToken))
                .get("items")).isEmpty();

        // **Revoking a grant that is no longer live is a 404 with its own code**, not a silent 204:
        // a person who mistyped the handle used to be told the share was gone while the real
        // grantee kept receiving the value. It is not the binding's code either — the next step is
        // `secret shares`, not `secret list`.
        String shares = "/api/v1/secrets/" + id + "/shares?skill=demo/vault-share&user="
                + collaborator;
        assertThat(delete(shares, ownerToken).statusCode()).as("the live grant").isEqualTo(204);
        HttpResponse<String> again = delete(shares, ownerToken);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(404);
        assertThat(again.body()).contains("credential_share_not_found");
    }

    /**
     * The declaration is a hint, and the metadata answer says which of it the caller still lacks.
     *
     * <p>Both lists are sent even when nothing is declared, and {@code credentials_required} never
     * changes with the caller — only {@code credentials_missing} does, because only it depends on
     * who is asking.
     */
    @Test
    void theDeclarationNamesWhatTheCallerIsMissing() {
        // A real published row: the metadata read resolves a version, so a bare skill will not do.
        String skillId = publishedSkill("vault-declared");
        // The **version's** frontmatter is what a metadata read sees; the skill row's copy is only
        // the fallback for a version that has none.
        jdbc.sql("UPDATE skill_version SET frontmatter = :fm WHERE skill_id = :id")
                .param("fm", "{\"metadata\":{\"requires\":{\"credentials\":"
                        + "[\"COOKIE\",\"TOKEN\",\"COOKIE\"]}}}")
                .param("id", skillId)
                .update();
        String token = token();

        HttpResponse<String> metadataResponse =
                get("/api/v1/skills/demo/vault-declared/metadata", token);
        JsonNode before = json(metadataResponse);
        // The duplicate collapses: the variable is the identity, not the line in the array.
        assertThat(before.get("credentials_required")).as(metadataResponse.body()).isNotNull();
        assertThat(before.get("credentials_required").toString()).isEqualTo("[\"COOKIE\",\"TOKEN\"]");
        assertThat(before.get("credentials_missing").toString()).isEqualTo("[\"COOKIE\",\"TOKEN\"]");
        assertThat(before.get("credentials_bound").asBoolean()).isFalse();

        postJson("/api/v1/secrets", token, json(Map.of(
                "name", "cookie",
                "value", "test-cookie-value",
                "skill", "demo/vault-declared",
                "env", "COOKIE")));

        JsonNode after = json(get("/api/v1/skills/demo/vault-declared/metadata", token));
        assertThat(after.get("credentials_required").toString()).isEqualTo("[\"COOKIE\",\"TOKEN\"]");
        assertThat(after.get("credentials_missing").toString()).isEqualTo("[\"TOKEN\"]");
        assertThat(after.get("credentials_bound").asBoolean()).isTrue();
    }

    /**
     * The same shape {@code SkillDetailIT} uses: a live skill with one published version. The
     * manifest is empty because this test never reads it — the metadata answer's version fields
     * come from the row.
     */
    private String publishedSkill(String name) {
        String skillId = Ulid.generate();
        String versionId = Ulid.generate();
        String at = "2026-10-20T09:12:00Z";
        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, public_rung, current_version_id,
                                   current_version_name, current_version_submitted_at,
                                   created_by, created_at, updated_at)
                VALUES (:id, :namespace, :name, :name, :name, '{}', 'private', NULL, :version,
                        '1.0.0', :at, :user, :at, :at)
                """)
                .param("id", skillId)
                .param("namespace", "01M3HTGC79VYJGM8BFXHX2QYNH")
                .param("name", name)
                .param("version", versionId)
                .param("at", at)
                .param("user", "01M3HTG7GCCVBGRPAFFSVSF12W")
                .update();
        jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, version, digest, file_count,
                                           total_bytes, changelog, source, submitted_by, submitted_at,
                                           state, state_at, first_published_at, title, description,
                                           frontmatter)
                VALUES (:id, :skill, 1, '1.0.0', :digest, 0, 0, '', 'zip', :user, :at,
                        'published', :at, :at, :name, :name, '{}')
                """)
                .param("id", versionId)
                .param("skill", skillId)
                .param("digest", "0".repeat(64))
                .param("user", "01M3HTG7GCCVBGRPAFFSVSF12W")
                .param("at", at)
                .param("name", name)
                .update();
        return skillId;
    }
}
