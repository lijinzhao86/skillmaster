package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Skill-level sharing end to end (ADR 0034): who can read a skill, who can add to it, who can publish
 * it, and who can hand it to somebody else.
 *
 * <p><strong>Two accounts, because one cannot tell the predicates apart.</strong> Almost everything
 * here is a difference between two people — the owner and a grantee, a viewer and an editor — and a
 * test acting as one account would only ever exercise the ownership branch, which was already true
 * before sharing existed.
 *
 * <p>Four levels are asserted, and the point is that they are four. An editor has the second and not
 * the third or the fourth, which is what makes a grant something other than a handed-over key: the
 * fourth is the one that would let a single grant spread with the owner seeing no link in the chain,
 * and the third is the one that decides what every reader of this server gets.
 *
 * <p>The shares are made over the API plane here, because that is the plane the CLI reaches — where
 * `share` and `--to` live. The browser plane's three routes call the same three use cases and are
 * covered in {@code WebSkillsIT}.
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillSharingIT extends AbstractAccountIT {

    private static final String SKILLS = "/api/v1/skills";

    private String owner;
    private String ownerToken;

    /**
     * The grantee, on both planes. Their browser is a second one rather than the shared `browser`,
     * because that one has to stay the owner's: the author plane's routes take the caller from the
     * session, so "the editor tried to publish" is only askable by a client signed in as the editor.
     */
    private String friend;
    private String friendToken;
    private Browser friendBrowser;

    /**
     * Registered friend-first so that the shared browser ends up signed in as the owner — whichever
     * account registers last owns that session, and every `webPost` in this class is an act on the
     * author plane, which is the owner's.
     */
    @BeforeEach
    void twoAccounts() {
        String friendPhone = randomPhone();
        friend = randomUsername();
        friendToken = tokenFor(registerAndSignIn(friend, friendPhone));
        friendBrowser = secondSignedInBrowser(friendPhone, PASSWORD);

        owner = randomUsername();
        ownerToken = tokenFor(registerAndSignIn(owner, randomPhone()));
    }

    @Test
    void aViewerGrantMakesTheSkillReadableAndNothingElse() {
        String digest = published();

        assertThat(share("viewer").statusCode()).isEqualTo(201);
        assertThat(readBodyOf(friendToken, digest).statusCode())
                .as("a grantee reads it, and by the same digest the owner does")
                .isEqualTo(200);
        assertThat(readBodyOf(ownerToken, digest).statusCode()).isEqualTo(200);

        // And can add nothing. This is the system's first 403, and it is available only because
        // existence is already confirmed — the skill is in the grantee's own listing, so a 404 would
        // be a false statement about it.
        HttpResponse<String> adding = addVersionAs(friendToken, "pdf-tools", "2.0.0");
        assertThat(adding.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(adding.body()).get("error").get("code").asText())
                .isEqualTo("forbidden");
        // No challenge. A 401 challenge would send a client to authenticate again, and signing in
        // again produces the same grant — a loop with no way out of it.
        assertThat(adding.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE))
                .as("403 means the credential is fine and the grant is too small")
                .isEmpty();
    }

    @Test
    void anEditorGrantLetsThemAddAVersionButNotPublishOrReshareIt() {
        published();

        assertThat(share("editor").statusCode()).isEqualTo(201);

        HttpResponse<String> added = addVersionAs(friendToken, "pdf-tools", "2.0.0");
        assertThat(added.statusCode()).as("adding failed: %s", added.body()).isEqualTo(201);
        assertThat(JSON.readTree(added.body()).get("skill_created").asBoolean())
                .as("the skill was already there: this route adds to one and cannot create one")
                .isFalse();

        // A draft, and the editor cannot make it live — publishing decides what every reader of this
        // server gets, so it stays the owner's and stays in a browser. The friend's own browser is
        // what asks, because the route takes the caller from the session.
        HttpResponse<String> publishing = webPostAs(friendBrowser,
                "/web/skills/" + owner + "/pdf-tools/publish", json(Map.of("version", "2.0.0")));
        assertThat(publishing.statusCode()).isEqualTo(403);

        // Nor pass it on: an editor who could re-share would let one grant spread without the owner
        // seeing a link in the chain.
        assertThat(postGrant(friendToken, "editor", owner).statusCode())
                .as("re-sharing is the owner's, not the editor's")
                .isEqualTo(403);
        assertThat(get(grantsPath(), friendToken).statusCode())
                .as("and who it is shared with is not the grantee's to see either")
                .isEqualTo(403);
    }

    @Test
    void aGrantChangesRoleRatherThanAccumulating() {
        published();

        assertThat(share("viewer").statusCode()).isEqualTo(201);
        assertThat(addVersionAs(friendToken, "pdf-tools", "2.0.0").statusCode())
                .as("a viewer cannot add")
                .isEqualTo(403);

        assertThat(share("editor").statusCode()).as("the same call is how a role moves").isEqualTo(201);
        assertThat(addVersionAs(friendToken, "pdf-tools", "2.0.0").statusCode()).isEqualTo(201);

        JsonNode grants = grantsOf(ownerToken);
        assertThat(grants).as("one entry per person, not one per grant").hasSize(1);
        assertThat(grants.get(0).get("handle").asText()).isEqualTo(friend);
        assertThat(grants.get(0).get("role").asText()).isEqualTo("editor");
        assertThat(count("SELECT count(*) FROM skill_grant")).as("the pair is the key").isEqualTo(1);
    }

    @Test
    void revokingTakesTheAccessAwayAndSayingItTwiceIsStillASuccess() {
        published();
        assertThat(share("viewer").statusCode()).isEqualTo(201);
        assertThat(readBodyOf(friendToken, digestOf("pdf-tools")).statusCode()).isEqualTo(200);

        HttpResponse<String> revoked = send(request(grantsPath() + "/" + friend, ownerToken)
                .DELETE().build());
        assertThat(revoked.statusCode()).isEqualTo(204);
        assertThat(revoked.body()).as("204 means 204: no body saying which of the two it was")
                .isEmpty();

        // The single indistinguishable 404, restored: not "you had access and lost it" but the same
        // nothing a stranger gets — which is also what the listing says.
        assertThat(getSkillAs(friendToken).statusCode()).isEqualTo(404);
        assertThat(listingNamesAs(friendToken)).doesNotContain("pdf-tools");

        // Idempotent, unlike every other "nothing there" in this system: the request asks for a state
        // that already holds, so saying it twice is the same success as saying it once.
        assertThat(send(request(grantsPath() + "/" + friend, ownerToken).DELETE().build()).statusCode())
                .isEqualTo(204);
        assertThat(count("SELECT count(*) FROM skill_grant")).isZero();
    }

    @Test
    void aGranteeSeesTheSharedSkillInTheirListingUnderItsOwnNamespace() {
        published();
        assertThat(share("viewer").statusCode()).isEqualTo(201);

        JsonNode listed = listingAs(friendToken);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("name").asText()).isEqualTo("pdf-tools");
        assertThat(listed.get(0).get("namespace").asText())
                .as("the owner's namespace, not the reader's — this is the field the row carries "
                        + "precisely so a caller does not have to supply it from the session")
                .isEqualTo(owner);
    }

    @Test
    void aViewerGrantIsNotEnoughToSeeItInTheAuthorPlane() {
        published();
        assertThat(share("viewer").statusCode()).isEqualTo(201);

        // The author's page shows drafts and diffs — a draft is a version you could act on — so a
        // viewer is not on it, even though the same skill is in their consumption listing above.
        assertThat(webListingAs(friendBrowser)).isEmpty();

        assertThat(share("editor").statusCode()).isEqualTo(201);
        assertThat(webListingAs(friendBrowser)).hasSize(1);
    }

    @Test
    void aSharedSkillIsNamedByItsOwnNamespaceOnTheAuthorPlane() {
        published();
        assertThat(share("editor").statusCode()).isEqualTo(201);

        JsonNode row = webListingAs(friendBrowser).get(0);
        assertThat(row.get("namespace").asText())
                .as("the owner's slug. Filling this in from the session would give the row an address "
                        + "that resolves to a different skill of the reader's, or to none at all")
                .isEqualTo(owner);
    }

    @Test
    void theBrowserPlaneSharesAndWithdrawsThroughTheSameRules() {
        published();
        String path = "/web/skills/" + owner + "/pdf-tools/grants";

        HttpResponse<String> granted = webPost(path, json(Map.of("handle", friend, "role", "editor")));
        assertThat(granted.statusCode()).as("granting failed: %s", granted.body()).isEqualTo(201);
        assertThat(JSON.readTree(granted.body()).get("role").asText()).isEqualTo("editor");

        JsonNode grants = JSON.readTree(webGet(path).body()).get("grants");
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).get("handle").asText()).isEqualTo(friend);

        // The grantee's own session cannot read this list either: the route calls the same use case
        // the API plane's does, so "the owner's view" means the same thing on both planes.
        assertThat(friendBrowser.get(uri(path)).statusCode()).isEqualTo(403);

        // And withdrawing is the same 204 the API plane gives — a state converging, so nothing on the
        // wire says whether a row was there to remove.
        assertThat(browser.delete(uri(path + "/" + friend)).statusCode()).isEqualTo(204);
        assertThat(JSON.readTree(webGet(path).body()).get("grants")).isEmpty();
    }

    @Test
    void sharingWithNobodyOrWithYourselfOrAtARoleThatIsNotOneIsRefused() {
        published();

        HttpResponse<String> stranger = postGrant(ownerToken, "viewer", randomUsername());
        assertThat(stranger.statusCode()).isEqualTo(400);
        assertThat(issue(stranger)).isEqualTo("no_such_user");

        HttpResponse<String> yourself = postGrant(ownerToken, "viewer", owner);
        assertThat(yourself.statusCode())
                .as("a grant pointing at the owner would make 'who owns this' answerable two ways")
                .isEqualTo(400);
        assertThat(issue(yourself)).isEqualTo("already_yours");

        HttpResponse<String> nonsense = postGrant(ownerToken, "owner", friend);
        assertThat(nonsense.statusCode())
                .as("`owner` is what owning the namespace gives you; it is not a role that can be given")
                .isEqualTo(400);
        assertThat(issue(nonsense)).isEqualTo("must_be_viewer_or_editor");
    }

    @Test
    void aStrangerGetsTheOrdinaryNothingRatherThanAForbidden() {
        published();

        // The distinction the 403 turns on: the grantee can see the skill and may not administer it,
        // so refusing them with a 403 tells them nothing they did not know. A stranger is not in that
        // position — for them the skill does not exist, and it has to keep looking that way.
        assertThat(postGrant(friendToken, "viewer", owner).statusCode()).isEqualTo(404);
        assertThat(get(grantsPath(), friendToken).statusCode()).isEqualTo(404);
        assertThat(send(request(grantsPath() + "/" + owner, friendToken).DELETE().build()).statusCode())
                .isEqualTo(404);
    }

    @Test
    void addingAVersionCannotBringASkillIntoBeing() {
        published();
        assertThat(share("editor").statusCode()).isEqualTo(201);

        // The route names its target, and this is the whole of what makes that safe: it adds to a
        // skill that is there. A route that created on demand would be a way to put a skill into
        // somebody else's namespace, which is exactly what the create route's "no namespace
        // parameter" rule exists to prevent.
        HttpResponse<String> invented = addVersionAs(friendToken, "invented", "1.0.0");
        assertThat(invented.statusCode()).isEqualTo(404);
        assertThat(listingNamesAs(ownerToken))
                .as("nothing was created in the owner's namespace")
                .containsExactly("pdf-tools");
    }

    @Test
    void addingAVersionRequiresTheAddressAndTheContentsToNameTheSameSkill() {
        published();
        assertThat(share("editor").statusCode()).isEqualTo(201);

        // Naming a target in the path is what makes this route different from the create one, and it
        // makes the name the second source for one fact — the first being the frontmatter. Without
        // this rule, `--to lark/pdf-tools` with a zip holding `other-skill/` would add that content to
        // `lark/pdf-tools`, which is a version of a skill nobody named anywhere in the request.
        HttpResponse<String> mismatched =
                addVersionNamed(friendToken, "pdf-tools", "something-else", "2.0.0");
        assertThat(mismatched.statusCode()).isEqualTo(400);
        assertThat(issue(mismatched)).isEqualTo("name_mismatch");
        assertThat(count("SELECT count(*) FROM skill_version")).as("nothing was written").isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Submits and publishes, so that the skill is addressable on the consumption plane. */
    private String published() {
        HttpResponse<String> submitted = submitAs(ownerToken, skill("pdf-tools", "1.0.0"));
        assertThat(submitted.statusCode()).as("submit failed: %s", submitted.body()).isEqualTo(201);

        HttpResponse<String> live = webPost("/web/skills/" + owner + "/pdf-tools/publish",
                json(Map.of("version", "1.0.0")));
        assertThat(live.statusCode()).as("publish failed: %s", live.body()).isEqualTo(200);
        return JSON.readTree(submitted.body()).get("version").get("digest").asText();
    }

    private String digestOf(String name) {
        HttpResponse<String> response = get(SKILLS + "/" + owner + "/" + name, ownerToken);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).get("version").get("digest").asText();
    }

    private HttpResponse<String> share(String role) {
        return postGrant(ownerToken, role, friend);
    }

    private HttpResponse<String> postGrant(String token, String role, String handle) {
        return send(request(grantsPath(), token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("handle", handle, "role", role))))
                .build());
    }

    private String grantsPath() {
        return SKILLS + "/" + owner + "/pdf-tools/grants";
    }

    private JsonNode grantsOf(String token) {
        HttpResponse<String> response = get(grantsPath(), token);
        assertThat(response.statusCode()).as("grants failed: %s", response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("grants");
    }

    private HttpResponse<String> getSkillAs(String token) {
        return get(SKILLS + "/" + owner + "/pdf-tools", token);
    }

    private HttpResponse<String> readBodyOf(String token, String digest) {
        return get(SKILLS + "/" + owner + "/pdf-tools@" + digest + "/body", token);
    }

    /** Adds a version through §4.3's editor route, which is the whole of `--to`. */
    private HttpResponse<String> addVersionAs(String token, String name, String version) {
        return addVersionNamed(token, name, name, version);
    }

    /** The same, with the address and the zip allowed to name different skills. */
    private HttpResponse<String> addVersionNamed(String token, String addressName,
            String contentName, String version) {
        Multipart multipart =
                Multipart.create().file("file", contentName + ".zip", skill(contentName, version));
        return send(request(SKILLS + "/" + owner + "/" + addressName + "/versions", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }

    private HttpResponse<String> submitAs(String token, byte[] zip) {
        Multipart multipart = Multipart.create().file("file", "pdf-tools.zip", zip);
        return send(request(SKILLS, token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }

    private JsonNode listingAs(String token) {
        HttpResponse<String> response = get(SKILLS, token);
        assertThat(response.statusCode()).as("listing failed: %s", response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("skills");
    }

    private List<String> listingNamesAs(String token) {
        List<String> names = new ArrayList<>();
        listingAs(token).forEach(card -> names.add(card.get("name").asText()));
        return names;
    }

    /** A write on the browser plane, as whoever that browser is signed in as. */
    private HttpResponse<String> webPostAs(Browser who, String path, String body) {
        return who.post(uri(path), body, who.csrfToken());
    }

    private JsonNode webListingAs(Browser who) {
        HttpResponse<String> response = who.get(uri("/web/skills"));
        assertThat(response.statusCode()).as("listing failed: %s", response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("skills");
    }

    /** A zip holding one skill, wrapped in a directory named after it. */
    private static byte[] skill(String name, String version) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(name + "/SKILL.md",
                "---\nname: " + name + "\ndescription: A skill used by the sharing tests\n"
                        + "version: \"" + version + "\"\n---\n# " + name + "\n");
        return Zips.ofText(files);
    }
}
