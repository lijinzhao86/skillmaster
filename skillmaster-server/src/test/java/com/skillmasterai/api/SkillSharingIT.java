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
 * Skill-level sharing end to end (v45, ADR 0138): a share is an invitation, and the rungs only
 * arrive when the invitee accepts.
 *
 * <p>The four levels are still asserted, and the point is that they are still four: accepting a
 * `write` invitation must not also hand over publishing or the ability to pass the skill on. What
 * changes is the first step — {@link #shareAndAccept} drives the invitation and the acceptance
 * through the product's own routes, because the grant is no longer a single call.
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillSharingIT extends AbstractAccountIT {

    private static final String SKILLS = "/api/v1/skills";
    private static final String NOTIFICATIONS = "/api/v1/notifications";

    private String owner;
    private String ownerToken;
    private String friend;
    private String friendToken;
    private Browser friendBrowser;

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
    void anInvitationGrantsNothingUntilItIsAccepted() {
        String digest = published();

        assertThat(invite("write").statusCode()).isEqualTo(201);
        assertThat(count("SELECT count(*) FROM skill_grant")).isZero();
        assertThat(count("SELECT count(*) FROM skill_invitation")).isEqualTo(1);
        assertThat(readBodyOf(friendToken, digest).statusCode())
                .as("an invitation is not a grant: before accepting, the skill is still not there")
                .isEqualTo(404);
        assertThat(listingNamesAs(friendToken)).doesNotContain("pdf-tools");

        HttpResponse<String> accepted = acceptFirstInvitation(friendToken);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(JSON.readTree(accepted.body()).get("role").asText()).isEqualTo("write");
        assertThat(count("SELECT count(*) FROM skill_invitation")).isZero();
        assertThat(count("SELECT count(*) FROM skill_grant")).isEqualTo(1);
        assertThat(readBodyOf(friendToken, digest).statusCode()).isEqualTo(200);
    }

    @Test
    void aBrowseRungMakesTheSkillReadableAndNothingElse() {
        String digest = published();
        shareAndAccept("browse");

        assertThat(readBodyOf(friendToken, digest).statusCode()).isEqualTo(200);
        assertThat(readBodyOf(ownerToken, digest).statusCode()).isEqualTo(200);

        HttpResponse<String> adding = addVersionAs(friendToken, "pdf-tools", "2.0.0");
        assertThat(adding.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(adding.body()).get("error").get("code").asText())
                .isEqualTo("forbidden");
        assertThat(adding.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
    }

    @Test
    void aWriteRungLetsThemAddAVersionButNotPublishOrReshareIt() {
        published();
        shareAndAccept("write");

        HttpResponse<String> added = addVersionAs(friendToken, "pdf-tools", "2.0.0");
        assertThat(added.statusCode()).as("adding failed: %s", added.body()).isEqualTo(201);
        assertThat(JSON.readTree(added.body()).get("skill_created").asBoolean()).isFalse();

        HttpResponse<String> publishing = webPostAs(friendBrowser,
                "/web/skills/" + owner + "/pdf-tools/publish", json(Map.of("version", "2.0.0")));
        assertThat(publishing.statusCode()).isEqualTo(403);

        assertThat(postInvitation(friendToken, "write", owner).statusCode())
                .as("re-sharing needs `admin`, not `write`")
                .isEqualTo(403);
        assertThat(get(grantsPath(), friendToken).statusCode()).isEqualTo(403);
    }

    @Test
    void aSecondInvitationMovesTheRoleRatherThanAccumulating() {
        published();
        shareAndAccept("browse");
        assertThat(addVersionAs(friendToken, "pdf-tools", "2.0.0").statusCode()).isEqualTo(403);

        shareAndAccept("write");
        assertThat(addVersionAs(friendToken, "pdf-tools", "2.0.0").statusCode()).isEqualTo(201);

        JsonNode grants = grantsOf(ownerToken);
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).get("handle").asText()).isEqualTo(friend);
        assertThat(grants.get(0).get("role").asText()).isEqualTo("write");
        assertThat(count("SELECT count(*) FROM skill_grant")).isEqualTo(1);
    }

    @Test
    void revokingTakesTheAccessAwayAndSayingItTwiceIsStillASuccess() {
        published();
        shareAndAccept("browse");
        assertThat(readBodyOf(friendToken, digestOf("pdf-tools")).statusCode()).isEqualTo(200);

        HttpResponse<String> revoked = send(request(grantsPath() + "/" + friend, ownerToken)
                .DELETE().build());
        assertThat(revoked.statusCode()).isEqualTo(204);
        assertThat(revoked.body()).isEmpty();

        assertThat(getSkillAs(friendToken).statusCode()).isEqualTo(404);
        assertThat(listingNamesAs(friendToken)).doesNotContain("pdf-tools");

        assertThat(send(request(grantsPath() + "/" + friend, ownerToken).DELETE().build())
                .statusCode()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM skill_grant")).isZero();
    }

    @Test
    void withdrawingAPendingInvitationClosesItsNotification() {
        published();
        assertThat(invite("write").statusCode()).isEqualTo(201);

        assertThat(send(request(SKILLS + "/" + owner + "/pdf-tools/invitations/" + friend,
                ownerToken).DELETE().build()).statusCode()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM skill_invitation")).isZero();
        assertThat(count("SELECT count(*) FROM skill_grant")).isZero();

        JsonNode row = firstNotification(friendToken);
        assertThat(row.get("state").asText()).isEqualTo("revoked");
    }

    @Test
    void aGranteeSeesTheSharedSkillInTheirListingUnderItsOwnNamespace() {
        published();
        shareAndAccept("browse");

        JsonNode listed = listingAs(friendToken);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("name").asText()).isEqualTo("pdf-tools");
        assertThat(listed.get(0).get("namespace").asText()).isEqualTo(owner);
    }

    @Test
    void aBrowseRungDoesNotPutItInTheAuthorPlaneListingButDoesOpenThePage() {
        published();
        assertThat(addVersionAs(ownerToken, "pdf-tools", "2.0.0").statusCode()).isEqualTo(201);
        shareAndAccept("browse");

        assertThat(webListingAs(friendBrowser)).isEmpty();

        HttpResponse<String> detail = friendBrowser.get(uri("/web/skills/" + owner + "/pdf-tools"));
        assertThat(detail.statusCode()).as("detail failed: %s", detail.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(detail.body());
        assertThat(body.get("versions")).hasSize(1);
        assertThat(body.get("versions").get(0).get("state").asText()).isEqualTo("published");
        assertThat(body.get("files")).isNotEmpty();

        assertThat(friendBrowser.get(uri("/web/skills/" + owner + "/pdf-tools@2.0.0")).statusCode())
                .isEqualTo(404);

        shareAndAccept("write");
        assertThat(webListingAs(friendBrowser)).hasSize(1);
    }

    @Test
    void aSharedSkillIsNamedByItsOwnNamespaceOnTheAuthorPlane() {
        published();
        shareAndAccept("write");

        JsonNode row = webListingAs(friendBrowser).get(0);
        assertThat(row.get("namespace").asText()).isEqualTo(owner);
    }

    @Test
    void theBrowserPlaneInvitesAndWithdrawsThroughTheSameRules() {
        published();
        String invitations = "/web/skills/" + owner + "/pdf-tools/invitations";

        HttpResponse<String> invited = webPost(invitations,
                json(Map.of("handle", friend, "role", "write")));
        assertThat(invited.statusCode()).as("inviting failed: %s", invited.body()).isEqualTo(201);
        assertThat(JSON.readTree(invited.body()).get("role").asText()).isEqualTo("write");

        JsonNode pending = JSON.readTree(webGet(invitations).body()).get("items");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("handle").asText()).isEqualTo(friend);

        // Before accepting, the invitee is still a stranger to the skill: the same 404 a stranger
        // gets, not a 403. An invitation grants nothing until it is answered.
        assertThat(friendBrowser.get(uri(invitations)).statusCode()).isEqualTo(404);

        assertThat(browser.delete(uri(invitations + "/" + friend)).statusCode()).isEqualTo(204);
        assertThat(JSON.readTree(webGet(invitations).body()).get("items")).isEmpty();
    }

    @Test
    void sharingWithNobodyOrWithYourselfOrAtARoleThatIsNotOneIsRefused() {
        published();

        HttpResponse<String> stranger = postInvitation(ownerToken, "browse", randomUsername());
        assertThat(stranger.statusCode()).isEqualTo(400);
        assertThat(issue(stranger)).isEqualTo("no_such_user");

        HttpResponse<String> yourself = postInvitation(ownerToken, "browse", owner);
        assertThat(yourself.statusCode()).isEqualTo(400);
        assertThat(issue(yourself)).isEqualTo("already_yours");

        HttpResponse<String> nonsense = postInvitation(ownerToken, "owner", friend);
        assertThat(nonsense.statusCode()).isEqualTo(400);
        assertThat(issue(nonsense)).isEqualTo("not_a_rung");

        HttpResponse<String> oldWord = postInvitation(ownerToken, "viewer", friend);
        assertThat(oldWord.statusCode()).isEqualTo(400);
        assertThat(issue(oldWord)).isEqualTo("not_a_rung");
    }

    @Test
    void aStrangerGetsTheOrdinaryNothingRatherThanAForbidden() {
        published();

        assertThat(postInvitation(friendToken, "browse", owner).statusCode()).isEqualTo(404);
        assertThat(get(grantsPath(), friendToken).statusCode()).isEqualTo(404);
        assertThat(send(request(grantsPath() + "/" + owner, friendToken).DELETE().build()).statusCode())
                .isEqualTo(404);
    }

    @Test
    void addingAVersionCannotBringASkillIntoBeing() {
        published();
        shareAndAccept("write");

        HttpResponse<String> invented = addVersionAs(friendToken, "invented", "1.0.0");
        assertThat(invented.statusCode()).isEqualTo(404);
        assertThat(listingNamesAs(ownerToken)).containsExactly("pdf-tools");
    }

    @Test
    void addingAVersionRequiresTheAddressAndTheContentsToNameTheSameSkill() {
        published();
        shareAndAccept("write");

        HttpResponse<String> mismatched =
                addVersionNamed(friendToken, "pdf-tools", "something-else", "2.0.0");
        assertThat(mismatched.statusCode()).isEqualTo(400);
        assertThat(issue(mismatched)).isEqualTo("name_mismatch");
        assertThat(count("SELECT count(*) FROM skill_version")).as("nothing was written").isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private String published() {
        HttpResponse<String> submitted = submitAs(ownerToken, skill("pdf-tools", "1.0.0"));
        assertThat(submitted.statusCode()).as("submit failed: %s", submitted.body()).isEqualTo(201);

        JsonNode submittedBody = JSON.readTree(submitted.body());
        HttpResponse<String> live = webPost("/web/skills/" + owner + "/pdf-tools/publish",
                json(Map.of("version",
                        submittedBody.get("version").get("draft_id").asText(),
                        "release_name", "1.0.0")));
        assertThat(live.statusCode()).as("publish failed: %s", live.body()).isEqualTo(200);
        return JSON.readTree(submitted.body()).get("version").get("digest").asText();
    }

    private String digestOf(String name) {
        HttpResponse<String> response = get(SKILLS + "/" + owner + "/" + name, ownerToken);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).get("version").get("digest").asText();
    }

    /** The product's whole path: invite, then the invitee accepts from their notification. */
    private void shareAndAccept(String role) {
        HttpResponse<String> invited = invite(role);
        assertThat(invited.statusCode()).as("inviting failed: %s", invited.body()).isEqualTo(201);
        HttpResponse<String> accepted = acceptFirstInvitation(friendToken);
        assertThat(accepted.statusCode()).as("accepting failed: %s", accepted.body()).isEqualTo(200);
    }

    private HttpResponse<String> invite(String role) {
        return postInvitation(ownerToken, role, friend);
    }

    private HttpResponse<String> postInvitation(String token, String role, String handle) {
        return send(request(SKILLS + "/" + owner + "/pdf-tools/invitations", token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("handle", handle, "role", role))))
                .build());
    }

    /** The invitee's one invitation, accepted through the notification's own route. */
    private HttpResponse<String> acceptFirstInvitation(String token) {
        JsonNode row = firstNotification(token);
        return send(request(NOTIFICATIONS + "/" + row.get("id").asText() + "/accept", token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    }

    private JsonNode firstNotification(String token) {
        HttpResponse<String> response = get(NOTIFICATIONS + "?status=all&per=50", token);
        assertThat(response.statusCode()).as("notifications failed: %s", response.body())
                .isEqualTo(200);
        JsonNode items = JSON.readTree(response.body()).get("items");
        assertThat(items).as("expected a pending invitation notification").isNotEmpty();
        return items.get(0);
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

    private HttpResponse<String> addVersionAs(String token, String name, String version) {
        return addVersionNamed(token, name, name, version);
    }

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

    private HttpResponse<String> webPostAs(Browser who, String path, String body) {
        return who.post(uri(path), body, who.csrfToken());
    }

    private JsonNode webListingAs(Browser who) {
        HttpResponse<String> response = who.get(uri("/web/skills"));
        assertThat(response.statusCode()).as("listing failed: %s", response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("skills");
    }

    private static byte[] skill(String name, String version) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(name + "/SKILL.md",
                "---\nname: " + name + "\ndescription: A skill used by the sharing tests\n"
                        + "version: \"" + version + "\"\n---\n# " + name + "\n");
        return Zips.ofText(files);
    }
}
