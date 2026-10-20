package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Browser;
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
 * The permission ladder, one rung at a time (v5 test-plan T1 and T2, plus v6's T19, T20, T23 and
 * T26).
 *
 * <p><strong>Why a matrix rather than one long test.</strong> The ladder's failure mode is a
 * capability that silently moves: an extra route admitted one rung too low looks exactly like a
 * correct answer, because every response is a well-formed success. The only way to see it is to ask
 * the same questions at each rung and compare what each one gets with what its rung promises.
 *
 * <p>The promise is the table in {@code v5-skill-management/technical-design.md} §门槛表, and the
 * matrix below is that table read back: {@code invoke} gets the four content reads but not the
 * manifest, {@code browse} adds the manifest, the author page's files and the comparison of two
 * published versions, {@code write} adds drafts (and comparing anything with one),
 * {@code maintain} adds publishing, discarding somebody else's draft and the revoked state,
 * {@code admin} adds the grant list and visibility. Deletion and restoration are above all five,
 * and every rung is checked against them.
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillRungsIT extends AbstractAccountIT {

    private static final String SKILLS = "/api/v1/skills";

    private String owner;
    private String ownerToken;
    private String friend;
    private String friendToken;
    private Browser friendBrowser;
    /** Somebody the grantees can try to grant to; never the owner, which is refused earlier. */
    private String other;

    /**
     * Every account but the owner registers before it, so the shared browser ends up signed in as
     * the owner: every {@code webPost} below is the owner's act, and the others act through their
     * own browser or the API plane.
     */
    @BeforeEach
    void threeAccounts() {
        other = randomUsername();
        registerAndSignIn(other, randomPhone());

        String friendPhone = randomPhone();
        friend = randomUsername();
        registerAndSignIn(friend, friendPhone);
        friendBrowser = secondSignedInBrowser(friendPhone, PASSWORD);
        friendToken = tokenOver(friendBrowser);

        owner = randomUsername();
        ownerToken = tokenFor(registerAndSignIn(owner, randomPhone()));
    }

    // ---------------------------------------------------------------------------------------
    // invoke
    // ---------------------------------------------------------------------------------------

    @Test
    void invokeReadsTheContentButNotTheManifest() {
        published();
        String draft = draftByOwner("2.0.0");
        grant("invoke");

        assertThat(api("metadata").statusCode()).isEqualTo(200);
        assertThat(api("body").statusCode()).isEqualTo(200);
        assertThat(api("files/references/notes.md").statusCode()).isEqualTo(200);
        assertThat(api("versions").statusCode()).isEqualTo(200);
        assertThat(body(api("versions")).get("versions"))
                .as("invoke lists published versions only")
                .hasSize(1);

        assertThat(api("").statusCode())
                .as("the detail carries the manifest, which is the first thing invoke does not get")
                .isEqualTo(403);

        HttpResponse<String> page = friendBrowser.get(uri(web("")));
        assertThat(page.statusCode()).as("the author page opens at invoke: %s", page.body())
                .isEqualTo(200);
        JsonNode pageBody = JSON.readTree(page.body());
        assertThat(pageBody.get("rung").asText()).isEqualTo("invoke");
        assertThat(pageBody.get("files")).isEmpty();
        assertThat(pageBody.get("resources").isNull()).isTrue();
        assertThat(pageBody.get("version").isNull()).isFalse();
        assertThat(pageBody.get("versions")).hasSize(1);
        assertThat(pageBody.get("versions").get(0).get("state").asText()).isEqualTo("published");

        assertThat(friendBrowser.get(uri(web("/body"))).statusCode()).isEqualTo(403);
        assertThat(friendBrowser.get(uri(web("/files/references/notes.md"))).statusCode())
                .isEqualTo(403);
        assertThat(friendBrowser.get(uri(web("/diff?to=1.0.0"))).statusCode()).isEqualTo(403);

        assertThat(friendBrowser.get(uri("/web/skills")).statusCode()).isEqualTo(200);
        assertThat(body(friendBrowser.get(uri("/web/skills"))).get("skills"))
                .as("the author-plane listing starts at write; invoke is a reader")
                .isEmpty();

        assertThat(addVersion(friendToken, "9.0.0").statusCode()).isEqualTo(403);
        assertThat(webPostAs(friendBrowser, "/publish",
                json(Map.of("version", draft, "release_name", "2.0.0"))).statusCode())
                .isEqualTo(403);
        assertThat(webPostAs(friendBrowser, "/discard", json(Map.of("version", draft))).statusCode())
                .as("at invoke the draft is not a version this caller can see, so it is the "
                        + "ordinary nothing rather than a refusal that would confirm one exists")
                .isEqualTo(404);
        HttpResponse<String> shareAttempt = postGrant(friendToken, "invoke");
        assertThat(shareAttempt.statusCode()).as("body: %s", shareAttempt.body())
                .isEqualTo(403);
        assertThat(friendBrowser.patch(uri(web("/visibility")),
                json(Map.of("visibility", "public", "public_rung", "invoke"))).statusCode())
                .isEqualTo(403);
        assertThat(webPostAs(friendBrowser, "/delete", "").statusCode()).isEqualTo(403);
    }

    // ---------------------------------------------------------------------------------------
    // browse
    // ---------------------------------------------------------------------------------------

    @Test
    void browseAddsTheManifestAndComparesPublishedVersionsButStillSeesNoDraft() {
        published();
        String second = draftByOwner("2.0.0");
        assertThat(webPost(web("/publish"), json(Map.of(
                "version", second, "release_name", "2.0.0"))).statusCode())
                .as("publishing the second version gives browse a real pair to compare")
                .isEqualTo(200);
        String draft = draftByOwner("3.0.0");
        grant("browse");

        assertThat(api("metadata").statusCode()).isEqualTo(200);
        assertThat(api("body").statusCode()).isEqualTo(200);
        assertThat(api("files/references/notes.md").statusCode()).isEqualTo(200);
        assertThat(api("versions").statusCode()).isEqualTo(200);
        HttpResponse<String> detail = api("");
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(detail.body()).get("files"))
                .as("browse is where the manifest starts")
                .isNotEmpty();

        HttpResponse<String> page = friendBrowser.get(uri(web("")));
        assertThat(page.statusCode()).isEqualTo(200);
        JsonNode pageBody = JSON.readTree(page.body());
        assertThat(pageBody.get("rung").asText()).isEqualTo("browse");
        assertThat(pageBody.get("files")).isNotEmpty();
        assertThat(pageBody.get("resources").isNull()).isFalse();
        assertThat(pageBody.get("versions")).hasSize(2);
        for (JsonNode each : pageBody.get("versions")) {
            assertThat(each.get("state").asText())
                    .as("the draft is not in the version table at browse")
                    .isEqualTo("published");
        }

        assertThat(friendBrowser.get(uri(web("/body"))).statusCode()).isEqualTo(200);
        assertThat(friendBrowser.get(uri(web("/files/references/notes.md"))).statusCode())
                .isEqualTo(200);
        HttpResponse<String> diff = friendBrowser.get(uri(web("/diff?from=1.0.0&to=2.0.0")));
        assertThat(diff.statusCode())
                .as("two published versions are browse's to compare (v6 lowered v5's write gate)")
                .isEqualTo(200);
        JsonNode compared = JSON.readTree(diff.body());
        assertThat(compared.get("from").asText()).isEqualTo("1.0.0");
        assertThat(compared.get("to").asText()).isEqualTo("2.0.0");
        assertThat(friendBrowser.get(uri(web("/diff?to=" + draft))).statusCode())
                .as("a draft is not a version browse can see, so the ordinary nothing")
                .isEqualTo(404);
        assertThat(friendBrowser.get(uri(web("/diff?from=" + draft + "&to=2.0.0"))).statusCode())
                .as("nor is an invisible draft usable as the baseline side")
                .isEqualTo(404);

        assertThat(addVersion(friendToken, "9.0.0").statusCode()).isEqualTo(403);
        assertThat(postGrant(friendToken, "browse").statusCode()).isEqualTo(403);
        assertThat(friendBrowser.patch(uri(web("/visibility")),
                json(Map.of("visibility", "private"))).statusCode()).isEqualTo(403);
    }

    // ---------------------------------------------------------------------------------------
    // write
    // ---------------------------------------------------------------------------------------

    @Test
    void writeAddsVersionsAndDiscardsItsOwnDraftButCannotPublishOrAdminister() {
        published();
        String ownersDraft = draftByOwner("2.0.0");
        grant("write");

        HttpResponse<String> added = addVersion(friendToken, "2.1.0");
        assertThat(added.statusCode()).as("write failed: %s", added.body()).isEqualTo(201);
        String ownDraft = JSON.readTree(added.body()).get("version").get("draft_id").asText();

        JsonNode pageBody = JSON.readTree(friendBrowser.get(uri(web(""))).body());
        assertThat(pageBody.get("rung").asText()).isEqualTo("write");
        assertThat(pageBody.get("versions"))
                .as("published plus both drafts: the owner's and this caller's")
                .hasSize(3);
        boolean draftVisible = false;
        for (JsonNode each : pageBody.get("versions")) {
            draftVisible |= "draft".equals(each.get("state").asText());
        }
        assertThat(draftVisible).as("write is where the draft rows appear").isTrue();

        assertThat(friendBrowser.get(uri(web("/diff?to=" + ownDraft))).statusCode())
                .as("a draft is write's to compare against")
                .isEqualTo(200);

        assertThat(friendBrowser.patch(uri(web("/versions/" + ownDraft + "/name")),
                json(Map.of("name", "mine"))).statusCode())
                .as("write may rename the draft it submitted")
                .isEqualTo(200);
        assertThat(friendBrowser.patch(uri(web("/versions/" + ownDraft + "/note")),
                json(Map.of("note", "mine"))).statusCode())
                .as("and give it a note")
                .isEqualTo(200);
        assertThat(friendBrowser.patch(uri(web("/versions/" + ownersDraft + "/name")),
                json(Map.of("name", "not-mine"))).statusCode())
                .as("the owner's draft is visible at write but not editable: 403, not 404")
                .isEqualTo(403);
        assertThat(friendBrowser.patch(uri(web("/versions/" + ownersDraft + "/note")),
                json(Map.of("note", "not-mine"))).statusCode())
                .isEqualTo(403);
        assertThat(friendBrowser.patch(uri(web("/versions/1.0.0/name")),
                json(Map.of("name", "not-mine"))).statusCode())
                .as("a published version has no rename entry; write is refused on permission (403) "
                        + "before the frozen rule could answer 400")
                .isEqualTo(403);

        JsonNode listing = body(friendBrowser.get(uri("/web/skills"))).get("skills");
        assertThat(listing.toString()).contains("pdf-tools");

        assertThat(webPostAs(friendBrowser, "/discard", json(Map.of("version", ownDraft)))
                .statusCode()).as("write may discard what it submitted").isEqualTo(200);
        assertThat(webPostAs(friendBrowser, "/discard", json(Map.of("version", ownersDraft)))
                .statusCode()).as("but not somebody else's draft").isEqualTo(403);

        assertThat(webPostAs(friendBrowser, "/publish",
                json(Map.of("version", ownersDraft, "release_name", "2.0.0"))).statusCode())
                .as("publishing is maintain")
                .isEqualTo(403);
        assertThat(postGrant(friendToken, "write").statusCode()).isEqualTo(403);
        assertThat(friendBrowser.patch(uri(web("/visibility")),
                json(Map.of("visibility", "public", "public_rung", "invoke"))).statusCode())
                .isEqualTo(403);
    }

    // ---------------------------------------------------------------------------------------
    // maintain
    // ---------------------------------------------------------------------------------------

    @Test
    void maintainPublishesRollsBackAndDiscardsAnyonesDraftButCannotAdminister() {
        published();
        String firstDraft = draftByOwner("2.0.0");
        grant("maintain");

        assertThat(friendBrowser.patch(uri(web("/versions/" + firstDraft + "/name")),
                json(Map.of("name", "renamed-by-maintain"))).statusCode())
                .as("maintain may rename somebody else's draft")
                .isEqualTo(200);
        assertThat(friendBrowser.patch(uri(web("/versions/" + firstDraft + "/note")),
                json(Map.of("note", "noted-by-maintain"))).statusCode())
                .as("and give it a note")
                .isEqualTo(200);

        assertThat(webPostAs(friendBrowser, "/discard", json(Map.of("version", firstDraft)))
                .statusCode()).as("maintain may discard the owner's draft").isEqualTo(200);

        String secondDraft = draftByOwner("2.1.0");
        assertThat(webPostAs(friendBrowser, "/publish",
                json(Map.of("version", secondDraft, "release_name", "2.0.0"))).statusCode())
                .as("maintain may publish")
                .isEqualTo(200);
        assertThat(friendBrowser.patch(uri(web("/versions/2.0.0/name")),
                json(Map.of("name", "too-late"))).statusCode())
                .as("a published version's name is frozen")
                .isEqualTo(400);
        assertThat(friendBrowser.patch(uri(web("/versions/2.0.0/note")),
                json(Map.of("note", "too-late"))).statusCode())
                .as("and so is its note")
                .isEqualTo(400);

        assertThat(webPostAs(friendBrowser, "/publish",
                json(Map.of("version", "1.0.0"))).statusCode())
                .as("a rollback is publishing an already-published version")
                .isEqualTo(200);

        assertThat(postGrant(friendToken, "maintain").statusCode()).isEqualTo(403);
        assertThat(friendBrowser.get(uri(web("/grants"))).statusCode()).isEqualTo(403);
        assertThat(friendBrowser.patch(uri(web("/visibility")),
                json(Map.of("visibility", "public", "public_rung", "invoke"))).statusCode())
                .isEqualTo(403);
    }

    // ---------------------------------------------------------------------------------------
    // v6 lifecycle: T20 and T23
    // ---------------------------------------------------------------------------------------

    /**
     * T20: a superseded version is history, not deprecated.
     *
     * <p>The absence is the assertion: no deprecate route, no {@code Deprecation} header, and the
     * consumption list still calls the row published-but-not-current. A future reintroduction of the
     * deprecated state would have to delete these checks to pass.
     */
    @Test
    void aSupersededVersionIsHistoryWithoutADeprecationSignal() {
        String first = published();
        String firstPublishedAt = null;
        for (JsonNode each : body(get(SKILLS + "/" + owner + "/pdf-tools/versions", ownerToken))
                .get("versions")) {
            if ("1.0.0".equals(each.get("name").asText())) {
                firstPublishedAt = each.get("published_at").asText();
            }
        }
        assertThat(firstPublishedAt).as("the first publish has a time").isNotNull();
        String second = draftByOwner("2.0.0");
        assertThat(webPost(web("/publish"), json(Map.of(
                "version", second, "release_name", "2.0.0"))).statusCode())
                .as("the second publish makes 1.0.0 history")
                .isEqualTo(200);
        grant("maintain");

        assertThat(webPostAs(friendBrowser, "/versions/" + first + "/deprecate", "{}").statusCode())
                .as("this version has no deprecate capability, so the route does not exist")
                .isEqualTo(404);

        HttpResponse<String> byName = get(SKILLS + "/" + owner + "/pdf-tools@1.0.0/body",
                friendToken);
        assertThat(byName.statusCode()).isEqualTo(200);
        assertThat(byName.headers().firstValue("Deprecation"))
                .as("no deprecation signal on a pinned read")
                .isEmpty();

        JsonNode history = null;
        for (JsonNode each : body(get(SKILLS + "/" + owner + "/pdf-tools/versions", friendToken))
                .get("versions")) {
            if ("1.0.0".equals(each.get("name").asText())) {
                history = each;
            }
        }
        assertThat(history).as("the superseded version stays in the consumption list").isNotNull();
        assertThat(history.get("state").asText()).isEqualTo("published");
        assertThat(history.get("status").asText()).isEqualTo("history");
        assertThat(history.get("is_current").asBoolean())
                .as("the pointer names 2.0.0, so this row is not current")
                .isFalse();
        assertThat(history.get("published_at").asText())
                .as("its publication date is the first time it went live, not the last change")
                .isEqualTo(firstPublishedAt);

        String digest = history.get("digest").asText();
        HttpResponse<String> byDigest = get(SKILLS + "/" + owner + "/pdf-tools@" + digest + "/body",
                friendToken);
        assertThat(byDigest.statusCode()).isEqualTo(200);
        assertThat(byDigest.headers().firstValue("Deprecation")).isEmpty();
    }

    /**
     * T23: who may move a version out of circulation, and who may take the whole service down.
     *
     * <p>Every rung is asked the same question. The two answers that matter are the boundary at
     * {@code maintain} and the refusal of skill-level offline for everyone but the owner — the
     * latter is ownership, not the top of the grant ladder.
     */
    @Test
    void lifecycleActionsFollowTheLadder() {
        String first = published();
        String second = draftByOwner("2.0.0");
        assertThat(webPost(web("/publish"), json(Map.of(
                "version", second, "release_name", "2.0.0"))).statusCode())
                .isEqualTo(200);

        grant("maintain");
        HttpResponse<String> delisted = webPostAs(friendBrowser, "/versions/" + first + "/revoke",
                "{}");
        assertThat(delisted.statusCode())
                .as("maintain may delist a historical version: %s", delisted.body())
                .isEqualTo(200);
        assertThat(friendBrowser.get(uri(web("/diff?from=" + first + "&to=" + second)))
                .statusCode())
                .as("a revoked side is maintain's to compare")
                .isEqualTo(200);
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools@1.0.0/body", friendToken).statusCode())
                .as("a revoked version is gone (410), not missing (404), by name")
                .isEqualTo(410);
        String revokedDigest = versionRow(friendBrowser, first).get("digest").asText();
        String pinnedDigest = revokedDigest.startsWith("sha256:")
                ? revokedDigest
                : "sha256:" + revokedDigest;
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools@" + pinnedDigest + "/body",
                friendToken).statusCode())
                .as("and the same answer by digest")
                .isEqualTo(410);
        grant("write");
        assertThat(friendBrowser.get(uri(web("/diff?from=" + first + "&to=" + second)))
                .statusCode())
                .as("write cannot see the revoked side, so the pair is simply absent")
                .isEqualTo(404);
        grant("maintain");
        assertThat(webPostAs(friendBrowser, "/versions/" + first + "/restore", "{}").statusCode())
                .as("and put it back")
                .isEqualTo(200);
        assertThat(webPostAs(friendBrowser, "/versions/" + second + "/revoke", "{}").statusCode())
                .as("the current version cannot be delisted directly")
                .isEqualTo(400);
        assertThat(versionRow(friendBrowser, second).get("status").asText())
                .as("the refused revoke left the current version live")
                .isEqualTo("live");
        assertThat(webPostAs(friendBrowser, "/offline", "").statusCode())
                .as("taking the service down is ownership, above every grant")
                .isEqualTo(403);
        assertThat(body(friendBrowser.get(uri(web("")))).get("offline_at").isNull())
                .as("the refused offline left the skill online")
                .isTrue();

        grant("admin");
        assertThat(webPostAs(friendBrowser, "/versions/" + first + "/revoke", "{}").statusCode())
                .isEqualTo(200);
        assertThat(webPostAs(friendBrowser, "/versions/" + first + "/restore", "{}").statusCode())
                .isEqualTo(200);
        assertThat(webPostAs(friendBrowser, "/offline", "").statusCode()).isEqualTo(403);

        for (String rung : List.of("write", "browse", "invoke")) {
            grant(rung);
            assertThat(webPostAs(friendBrowser, "/versions/" + first + "/revoke", "{}").statusCode())
                    .as("%s cannot delist a version", rung)
                    .isEqualTo(403);
            assertThat(webPostAs(friendBrowser, "/versions/" + first + "/restore", "{}").statusCode())
                    .as("%s cannot restore a version", rung)
                    .isEqualTo(403);
            assertThat(webPostAs(friendBrowser, "/offline", "").statusCode())
                    .as("%s cannot take the skill offline", rung)
                    .isEqualTo(403);
            assertThat(versionRow(friendBrowser, first).get("state").asText())
                    .as("no refused action at %s changed the historical version", rung)
                    .isEqualTo("published");
            assertThat(body(friendBrowser.get(uri(web("")))).get("offline_at").isNull())
                    .as("nor did it take the skill offline")
                    .isTrue();
        }

        assertThat(webPost(web("/offline"), "").statusCode())
                .as("the owner takes the skill offline")
                .isEqualTo(200);
        assertThat(webPost(web("/online"), "").statusCode())
                .as("and brings it back")
                .isEqualTo(200);

        // T26: a revoked version stays gone after the skill itself is soft-deleted and restored.
        assertThat(webPost(web("/versions/" + first + "/revoke"), "{}").statusCode())
                .isEqualTo(200);
        assertThat(webPost(web("/delete"), "").statusCode()).isEqualTo(204);
        assertThat(webPost(web("/restore"), "").statusCode()).isEqualTo(204);
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools@1.0.0/body", friendToken).statusCode())
                .as("restoring the skill does not resurrect the revoked version")
                .isEqualTo(410);
    }

    // ---------------------------------------------------------------------------------------
    // admin
    // ---------------------------------------------------------------------------------------

    @Test
    void adminManagesGrantsAndVisibilityButDeletionStaysTheOwners() {
        published();
        grant("admin");

        assertThat(friendBrowser.get(uri(web("/grants"))).statusCode()).isEqualTo(200);
        assertThat(postGrant(friendToken, "browse").statusCode()).isEqualTo(201);
        assertThat(send(request(SKILLS + "/" + owner + "/pdf-tools/grants/" + other,
                friendToken).DELETE().build()).statusCode()).isEqualTo(204);

        assertThat(friendBrowser.patch(uri(web("/visibility")),
                json(Map.of("visibility", "public", "public_rung", "invoke"))).statusCode())
                .isEqualTo(204);

        assertThat(webPostAs(friendBrowser, "/delete", "").statusCode())
                .as("deleting is above the top grantable rung")
                .isEqualTo(403);
        assertThat(webPostAs(friendBrowser, "/restore", "").statusCode())
                .as("the skill is not deleted, so restoration answers the ordinary nothing")
                .isEqualTo(404);
        assertThat(webPostAs(friendBrowser, "/owner", "").statusCode())
                .as("there is no route that changes ownership")
                .isEqualTo(404);
    }

    // ---------------------------------------------------------------------------------------
    // the rung vocabulary itself
    // ---------------------------------------------------------------------------------------

    @Test
    void theOwnerRungAndTheRetiredWordsAreRefusedRatherThanMapped() {
        published();

        assertThat(issue(postGrant(ownerToken, "owner"))).isEqualTo("not_a_rung");
        assertThat(issue(postGrant(ownerToken, "viewer"))).isEqualTo("not_a_rung");
        assertThat(issue(postGrant(ownerToken, "editor")))
                .as("the retired vocabulary is refused, not silently translated")
                .isEqualTo("not_a_rung");
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private HttpResponse<String> api(String suffix) {
        return get(SKILLS + "/" + owner + "/pdf-tools" + (suffix.isEmpty() ? "" : "/" + suffix),
                friendToken);
    }

    private String web(String suffix) {
        return "/web/skills/" + owner + "/pdf-tools" + suffix;
    }

    /** One row of the author version table, by its row id — the address the UI acts on. */
    private JsonNode versionRow(Browser who, String code) {
        for (JsonNode each : body(who.get(uri(web("")))).get("versions")) {
            if (code.equals(each.get("code").asText())) {
                return each;
            }
        }
        throw new AssertionError("no version row " + code + " for " + who);
    }

    private String published() {
        HttpResponse<String> submitted = submit(skill("1.0.0"));
        assertThat(submitted.statusCode()).as("submit failed: %s", submitted.body()).isEqualTo(201);
        String draft = JSON.readTree(submitted.body()).get("version").get("draft_id").asText();
        assertThat(webPost(web("/publish"), json(Map.of(
                "version", draft, "release_name", "1.0.0"))).statusCode())
                .as("publish failed")
                .isEqualTo(200);
        return draft;
    }

    private String draftByOwner(String version) {
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", skill(version));
        HttpResponse<String> added = send(request(SKILLS + "/" + owner + "/pdf-tools/versions",
                ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
        assertThat(added.statusCode()).as("adding a draft failed: %s", added.body())
                .isEqualTo(201);
        return JSON.readTree(added.body()).get("version").get("draft_id").asText();
    }

    private HttpResponse<String> addVersion(String token, String version) {
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", skill(version));
        return send(request(SKILLS + "/" + owner + "/pdf-tools/versions", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }

    private HttpResponse<String> submit(byte[] zip) {
        Multipart multipart = Multipart.create().file("file", "pdf-tools.zip", zip);
        return send(request(SKILLS, ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }

    private HttpResponse<String> grant(String role) {
        HttpResponse<String> granted = postGrant(ownerToken, role, friend);
        assertThat(granted.statusCode()).as("granting %s: %s", role, granted.body())
                .isEqualTo(201);
        return granted;
    }

    private HttpResponse<String> postGrant(String token, String role) {
        return postGrant(token, role, other);
    }

    private HttpResponse<String> postGrant(String token, String role, String handle) {
        return send(request(SKILLS + "/" + owner + "/pdf-tools/grants", token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("handle", handle, "role", role))))
                .build());
    }

    private HttpResponse<String> webPostAs(Browser who, String suffix, String body) {
        return who.post(uri(web(suffix)), body, who.csrfToken());
    }

    /** Signs a key over somebody else's browser — the only way a second account gets a credential. */
    private String tokenOver(Browser who) {
        who.get(uri(SESSION));
        HttpResponse<String> response = who.post(uri(KEYS),
                json(Map.of("name", "harness", "scopes", List.of("skills:read", "skills:write"))),
                who.csrfToken());
        assertThat(response.statusCode()).as("signing the friend's key: %s", response.body())
                .isEqualTo(201);
        return JSON.readTree(response.body()).get("token").asText();
    }

    private static byte[] skill(String version) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pdf-tools/SKILL.md",
                "---\nname: pdf-tools\ndescription: a skill for the rung matrix\n"
                        + "version: \"" + version + "\"\n"
                        + "metadata:\n  when_to_use: 要动 PDF 的时候用\n---\nbody " + version + "\n");
        files.put("pdf-tools/references/notes.md", "notes\n");
        return Zips.ofText(files);
    }
}
