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
 * v5's metadata routes on the browser plane: visibility, deletion / restoration, and access
 * requests (ADR 0048, 0050, 0052; test-plan T4–T7 and T9–T13).
 *
 * <p><strong>Three accounts, because the predicates are differences between people.</strong> The
 * owner is the shared browser; the friend is somebody the owner can grant to and whose own session
 * asks the questions a grantee asks; the stranger was never granted anything, and is what "public"
 * is measured against.
 *
 * <p>What these tests are for is the half that is easy to lose: a grantee at {@code admin} may
 * share but <em>not</em> delete, a deleted skill answers the same 404 as one that never existed,
 * and a request for a rung grants nothing until somebody approves it.
 */
@Sql("/sql/truncate-business-tables.sql")
class WebSkillMetadataIT extends AbstractAccountIT {

    private static final String SKILLS = "/api/v1/skills";

    private String owner;
    private String ownerToken;
    private String friend;
    private String friendToken;
    private Browser friendBrowser;
    private String stranger;
    private String strangerToken;
    private Browser strangerBrowser;

    /**
     * Stranger first, then friend, then owner: the shared {@link #browser} is signed in as whoever
     * registered last, and every {@code webPost} in this class is the owner's act. Each account's
     * token is signed while that person still holds the shared session, and its own browser is a
     * second client for the same person.
     */
    @BeforeEach
    void threeAccounts() {
        String strangerPhone = randomPhone();
        stranger = randomUsername();
        strangerToken = tokenFor(registerAndSignIn(stranger, strangerPhone));
        strangerBrowser = secondSignedInBrowser(strangerPhone, PASSWORD);

        String friendPhone = randomPhone();
        friend = randomUsername();
        friendToken = tokenFor(registerAndSignIn(friend, friendPhone));
        friendBrowser = secondSignedInBrowser(friendPhone, PASSWORD);

        owner = randomUsername();
        ownerToken = tokenFor(registerAndSignIn(owner, randomPhone()));
    }

    // ---------------------------------------------------------------------------------------
    // T5 / T9–T12: deletion is the owner's, and it is reversible
    // ---------------------------------------------------------------------------------------

    @Test
    void anAdminGranteeStillCannotDeleteAndTheApiPlaneHasNoSuchRoute() {
        published();
        assertThat(grant("admin").statusCode()).isEqualTo(201);

        HttpResponse<String> byGrantee = friendBrowser.post(uri(path("/delete")), "",
                friendBrowser.csrfToken());
        assertThat(byGrantee.statusCode())
                .as("`admin` is the top grantable rung, and deletion is deliberately above it")
                .isEqualTo(403);

        HttpResponse<String> byApi = send(request(SKILLS + "/" + owner + "/pdf-tools", ownerToken)
                .DELETE().build());
        assertThat(byApi.statusCode())
                .as("the API-plane delete is the route ADR 0046 took away, even for its owner; the "
                        + "path still serves GET, so the answer is 405 with an Allow, not a 403")
                .isEqualTo(405);
        assertThat(byApi.headers().firstValue(HttpHeaders.ALLOW))
                .as("RFC 9110 requires the header on a 405, and it says which methods would work")
                .hasValueSatisfying(allow -> {
                    List<String> methods = List.of(allow.split(",")).stream()
                            .map(String::trim)
                            .map(String::toUpperCase)
                            .toList();
                    assertThat(methods).contains("GET");
                    assertThat(methods)
                            .as("a read route, and only a read route")
                            .allMatch(method -> method.equals("GET") || method.equals("HEAD"));
                });

        HttpResponse<String> byOwner = webPost(path("/delete"), "");
        assertThat(byOwner.statusCode()).as("delete failed: %s", byOwner.body()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'delete'"))
                .as("the successful deletion left its trace")
                .isEqualTo(1);
    }

    @Test
    void aDeletedSkillIsTheSameNothingEverywhereAndComesBackIntact() {
        String digest = published();
        // `admin`, so the restore attempt below tests "not the owner", not "too low a rung".
        assertThat(grant("admin").statusCode()).isEqualTo(201);

        assertThat(webPost(path("/delete"), "").statusCode()).isEqualTo(204);

        // One answer for "deleted" and "never existed", on both planes.
        HttpResponse<String> deleted = get(SKILLS + "/" + owner + "/pdf-tools", ownerToken);
        HttpResponse<String> never = get(SKILLS + "/" + owner + "/no-such-skill", ownerToken);
        assertThat(deleted.statusCode()).isEqualTo(404);
        assertThat(never.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(deleted.body())).isEqualTo(JSON.readTree(never.body()));
        HttpResponse<String> authorDeleted = webGet(path(""));
        HttpResponse<String> authorNever = webGet("/web/skills/" + owner + "/no-such-skill");
        assertThat(authorDeleted.statusCode()).isEqualTo(404);
        assertThat(authorNever.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(authorDeleted.body())).isEqualTo(JSON.readTree(authorNever.body()));

        JsonNode deletedList = body(webGet("/web/skills?state=deleted")).get("skills");
        assertThat(deletedList).hasSize(1);
        JsonNode row = deletedList.get(0);
        assertThat(row.get("name").asText()).isEqualTo("pdf-tools");
        assertThat(row.get("deleted_at").isNull()).as("the row carries when, for 删除于 …").isFalse();
        assertThat(row.get("deleted_at").asText()).isNotBlank();

        // The deleted list is ownership's question alone: an `admin` grantee does not see it, even
        // though the same skill was in their live view a moment ago. This is why the grant above
        // is `admin`: the refusal is about ownership, not about being one rung too low.
        HttpResponse<String> asGrantee = friendBrowser.get(uri("/web/skills?state=deleted"));
        assertThat(JSON.readTree(asGrantee.body()).get("skills")).isEmpty();

        // Restoring is the owner's too, and a non-owner gets the ordinary nothing — not a 403,
        // which would confirm the deleted skill exists.
        assertThat(friendBrowser.post(uri(path("/restore")), "", friendBrowser.csrfToken())
                .statusCode()).isEqualTo(404);

        // The name stays taken while the row is deleted: a submission is refused rather than
        // resurrecting the skill or creating a second one under the same name.
        HttpResponse<String> resubmitted = submit(skill("pdf-tools", "9.9.9"));
        assertThat(resubmitted.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(resubmitted.body()).get("error").get("code").asText())
                .as("ADR 0071: the code names the reason, so the page can offer 去恢复")
                .isEqualTo("skill_deleted");
        assertThat(count("SELECT count(*) FROM skill WHERE name = 'pdf-tools'")).isEqualTo(1);

        assertThat(webPost(path("/restore"), "").statusCode()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'restore'")).isEqualTo(1);

        // Everything the deletion left alone is back: the address, the version, the grant.
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools", ownerToken).statusCode()).isEqualTo(200);
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools@" + digest + "/body", ownerToken)
                .statusCode()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM skill_grant WHERE grantee_id ="
                + " (SELECT id FROM app_user WHERE handle = :handle)", Map.of("handle", friend)))
                .isEqualTo(1);
        assertThat(body(webGet("/web/skills?state=deleted")).get("skills")).isEmpty();
    }

    /**
     * T12: the deleted list is ownership's question, in both directions. The live list shows what
     * was shared with you; this one does not, because restoring is the owner's alone.
     */
    @Test
    void theDeletedListShowsOnlyWhatTheCallerOwns() {
        published();
        // The precondition T12 names: the friend also holds `maintain` on the owner's live skill,
        // so "the live list shows it" and "the deleted list still does not" can both be asserted.
        grant("maintain");
        assertThat(JSON.readTree(friendBrowser.get(uri("/web/skills")).body()).get("skills"))
                .isNotEmpty();

        // A second owner with a skill of their own, so "only mine" has something to exclude.
        HttpResponse<String> friendsSubmission = submitAs(friendToken, "notes", "1.0.0");
        assertThat(friendsSubmission.statusCode())
                .as("the friend's submission: %s", friendsSubmission.body())
                .isEqualTo(201);
        String friendsDraft =
                JSON.readTree(friendsSubmission.body()).get("version").get("draft_id").asText();
        publishAs(friendBrowser, friend, "notes", friendsDraft, "1.0.0");

        assertThat(webPost(path("/delete"), "").statusCode()).isEqualTo(204);
        assertThat(friendBrowser.post(uri("/web/skills/" + friend + "/notes/delete"), "",
                friendBrowser.csrfToken()).statusCode()).isEqualTo(204);

        List<String> owners = names(body(webGet("/web/skills?state=deleted")).get("skills"));
        List<String> friends = names(JSON.readTree(
                friendBrowser.get(uri("/web/skills?state=deleted")).body()).get("skills"));
        assertThat(owners).containsExactly("pdf-tools");
        assertThat(friends).containsExactly("notes");
    }

    private static List<String> names(JsonNode rows) {
        List<String> names = new java.util.ArrayList<>();
        rows.forEach(row -> names.add(row.get("name").asText()));
        return names;
    }

    private void publishAs(Browser who, String namespace, String name, String draftId,
            String releaseName) {
        HttpResponse<String> published = who.post(
                uri("/web/skills/" + namespace + "/" + name + "/publish"),
                json(Map.of("version", draftId, "release_name", releaseName)),
                who.csrfToken());
        assertThat(published.statusCode()).as("publishing %s: %s", name, published.body())
                .isEqualTo(200);
    }

    private HttpResponse<String> submitAs(String token, String name, String version) {
        Multipart multipart =
                Multipart.create().file("file", name + ".zip", skill(name, version));
        return send(request(SKILLS, token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }

    // ---------------------------------------------------------------------------------------
    // T4 / T6 / T7: visibility hands out exactly one rung
    // ---------------------------------------------------------------------------------------

    @Test
    void publicHandsOutTheChosenRungAndNothingAboveIt() {
        published();

        // Private: a stranger sees nothing at all, writes included.
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools", strangerToken).statusCode())
                .isEqualTo(404);
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools/metadata", strangerToken).statusCode())
                .isEqualTo(404);
        assertThat(addVersion(strangerToken, "9.0.0").statusCode())
                .as("private means the skill does not exist, not that the rung is too low")
                .isEqualTo(404);

        assertThat(visibility("public", "invoke").statusCode()).isEqualTo(204);
        JsonNode detail = body(webGet(path("")));
        assertThat(detail.get("visibility").asText()).isEqualTo("public");
        assertThat(detail.get("public_rung").asText()).isEqualTo("invoke");

        // `invoke`: metadata, body and a named file are reachable; the manifest is not; writes are not.
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools/metadata", strangerToken).statusCode())
                .isEqualTo(200);
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools/body", strangerToken).statusCode())
                .isEqualTo(200);
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools/files/references/notes.md",
                strangerToken).statusCode()).isEqualTo(200);
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools", strangerToken).statusCode())
                .as("the detail carries the manifest, which is `browse`")
                .isEqualTo(403);
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools/versions", strangerToken).statusCode())
                .isEqualTo(200);
        assertThat(addVersion(strangerToken, "9.0.0").statusCode())
                .as("public invoke is a read rung; writing is a 403 because existence is known")
                .isEqualTo(403);
        assertThat(strangerBrowser.get(uri(path("/diff?to=1.0.0"))).statusCode())
                .as("comparison starts at browse; the public rung is invoke")
                .isEqualTo(403);

        // The card finds it, and `when_to_use` comes from `frontmatter.metadata`.
        // v14: the public plane is its own scope; a skill the stranger never joined is found there,
        // not in their library.
        JsonNode hits = body(get(SKILLS + "?scope=public&q=pdf", strangerToken)).get("skills");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("when_to_use").asText()).isEqualTo("要动 PDF 的时候用");

        // A writer's grant is higher than a public `invoke`: it is the higher of the two, not an
        // override in either direction (ADR 0048).
        assertThat(grant("write").statusCode()).isEqualTo(201);
        HttpResponse<String> added = addVersion(friendToken, "2.0.0");
        assertThat(added.statusCode())
                .as("the granted rung is above the public one")
                .isEqualTo(201);
        String secondDraft = JSON.readTree(added.body()).get("version").get("draft_id").asText();
        assertThat(friendBrowser.get(uri(path(""))).statusCode()).isEqualTo(200);

        // Raising the public rung hands the stranger the manifest too — and still no writes.
        assertThat(visibility("public", "browse").statusCode()).isEqualTo(204);
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools", strangerToken).statusCode())
                .isEqualTo(200);
        assertThat(addVersion(strangerToken, "3.0.0").statusCode()).isEqualTo(403);
        assertThat(webPostAs(friendBrowser, path("/publish"),
                json(Map.of("version", secondDraft))).statusCode())
                .as("`write` is not `maintain`").isEqualTo(403);

        // The owner publishes the draft so the public has a real pair to compare: the same
        // published-versus-published comparison a named `browse` grantee gets (v6's rule).
        assertThat(webPost(path("/publish"),
                json(Map.of("version", secondDraft, "release_name", "2.0.0"))).statusCode())
                .as("the owner publishes the second version")
                .isEqualTo(200);
        HttpResponse<String> diff = strangerBrowser.get(uri(path("/diff?from=1.0.0&to=2.0.0")));
        assertThat(diff.statusCode())
                .as("public browse compares two published versions: %s", diff.body())
                .isEqualTo(200);
        JsonNode compared = JSON.readTree(diff.body());
        assertThat(compared.get("from").asText()).isEqualTo("1.0.0");
        assertThat(compared.get("to").asText()).isEqualTo("2.0.0");

        // Back to private: the stranger loses everything again.
        assertThat(visibility("private", null).statusCode()).isEqualTo(204);
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools/metadata", strangerToken).statusCode())
                .isEqualTo(404);
    }

    @Test
    void changingVisibilityNeedsAdmin() {
        published();
        assertThat(grant("maintain").statusCode()).isEqualTo(201);

        assertThat(friendBrowser.patch(uri(path("/visibility")),
                json(Map.of("visibility", "public", "public_rung", "browse"))).statusCode())
                .as("`maintain` publishes and discards; visibility is `admin`")
                .isEqualTo(403);

        assertThat(grant("admin").statusCode()).isEqualTo(201);
        assertThat(friendBrowser.patch(uri(path("/visibility")),
                json(Map.of("visibility", "public", "public_rung", "browse"))).statusCode())
                .isEqualTo(204);
    }

    // ---------------------------------------------------------------------------------------
    // T13: a request is not a grant
    // ---------------------------------------------------------------------------------------

    @Test
    void aRequestGrantsNothingUntilItIsApproved() {
        published();
        assertThat(visibility("public", "invoke").statusCode()).isEqualTo(204);

        // The friend can see it (public invoke) and asks for write.
        assertThat(requestAs(friendBrowser, "write").statusCode()).isEqualTo(201);

        // The request itself is not a grant: the author page is still the `invoke` one (information
        // and versions, no files), and a submission is still refused.
        HttpResponse<String> page = friendBrowser.get(uri(path("")));
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(page.body()).get("files")).isEmpty();
        assertThat(addVersion(friendToken, "2.0.0").statusCode()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM skill_grant")).isZero();

        // Somebody who cannot see the skill cannot ask about it either: private means "nobody knows
        // it is there", including that there is something to ask for.
        assertThat(visibility("private", null).statusCode()).isEqualTo(204);
        assertThat(requestAs(strangerBrowser, "write").statusCode()).isEqualTo(404);
        assertThat(visibility("public", "invoke").statusCode()).isEqualTo(204);

        JsonNode pending = body(webGet(path("/access-requests")));
        assertThat(pending.get("total").asInt()).isEqualTo(1);
        assertThat(pending.get("requests").get(0).get("handle").asText()).isEqualTo(friend);
        assertThat(pending.get("requests").get(0).get("wanted_role").asText()).isEqualTo("write");
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'request'")).isEqualTo(1);

        // The queue is `admin`'s. The requester is at `invoke` and is refused with a 403 — they can
        // see the skill, so a 404 would be false — and so is a `write` grantee.
        assertThat(friendBrowser.get(uri(path("/access-requests"))).statusCode()).isEqualTo(403);
        assertThat(grant("write").statusCode()).isEqualTo(201);
        assertThat(friendBrowser.get(uri(path("/access-requests"))).statusCode()).isEqualTo(403);

        // Approval grants the approver's rung, not the requested one.
        assertThat(approve("maintain").statusCode()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM skill_access_request")).isZero();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'approve'")).isEqualTo(1);
        assertThat(friendBrowser.get(uri(path(""))).statusCode())
                .as("maintain is above browse, so the manifest is readable")
                .isEqualTo(200);
        HttpResponse<String> added = addVersion(friendToken, "2.0.0");
        assertThat(added.statusCode()).isEqualTo(201);
        String addedDraft = JSON.readTree(added.body()).get("version").get("draft_id").asText();
        assertThat(webPostAs(friendBrowser, path("/publish"), json(Map.of(
                "version", addedDraft, "release_name", "2.0.0")))
                .statusCode()).as("the approved rung is `maintain`").isEqualTo(200);

        // Answering the same request twice is the ordinary 404: it is gone.
        assertThat(approve("maintain").statusCode()).isEqualTo(404);

        // `maintain` has one rung above it and can ask for it; withdrawing removes it.
        assertThat(requestAs(friendBrowser, "admin").statusCode()).isEqualTo(201);
        assertThat(friendBrowser.delete(uri(path("/access-requests/me"))).statusCode())
                .isEqualTo(204);
        assertThat(body(webGet(path("/access-requests"))).get("total").asInt()).isZero();

        // Denial closes the request and changes no grant.
        assertThat(requestAs(friendBrowser, "admin").statusCode()).isEqualTo(201);
        assertThat(webPost(path("/access-requests/" + friend + "/deny"), "").statusCode())
                .isEqualTo(204);
        assertThat(count("SELECT count(*) FROM skill_access_request")).isZero();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'deny'")).isEqualTo(1);

        // A rung at or below one's own is the caller's mistake, not a refused capability.
        assertThat(requestAs(friendBrowser, "invoke").statusCode()).isEqualTo(400);
        assertThat(requestAs(strangerBrowser, "invoke").statusCode())
                .as("the public rung already hands them `invoke`; there is nothing to ask for")
                .isEqualTo(400);

        // `admin` is the top grantable rung: there is nothing above it to ask for either.
        assertThat(grant("admin").statusCode()).isEqualTo(201);
        assertThat(requestAs(friendBrowser, "admin").statusCode())
                .as("approval raised them to admin; the top of the ladder has nothing to request")
                .isEqualTo(400);
    }

    @Test
    void repeatingARequestMovesThePendingOneRatherThanAddingASecond() {
        published();
        assertThat(visibility("public", "invoke").statusCode()).isEqualTo(204);

        assertThat(requestAs(friendBrowser, "write").statusCode()).isEqualTo(201);
        assertThat(requestAs(friendBrowser, "maintain").statusCode()).isEqualTo(201);
        // A refused repeat must not overwrite the pending one (validated before written).
        assertThat(requestAs(friendBrowser, "invoke").statusCode()).isEqualTo(400);

        JsonNode pending = body(webGet(path("/access-requests")));
        assertThat(pending.get("total").asInt()).isEqualTo(1);
        assertThat(pending.get("requests").get(0).get("wanted_role").asText())
                .isEqualTo("maintain");
        assertThat(count("SELECT count(*) FROM skill_access_request")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'request'"))
                .as("every accepted ask leaves a trace, repeats included")
                .isEqualTo(2);
    }

    /**
     * The author detail is shaped by the caller's rung, and says what only the server knows
     * (test-plan T16): the standing, the caller's own pending request, and who submitted each
     * version. Every one of these is what the page draws a section or a button from.
     */
    @Test
    void theAuthorDetailSaysTheCallersStandingAndWhatIsPending() {
        published();
        // A draft the browse caller must not see, and a second version for the owner's list.
        assertThat(addVersion(ownerToken, "2.0.0").statusCode()).isEqualTo(201);

        JsonNode asOwner = body(webGet(path("")));
        assertThat(asOwner.get("rung").asText()).isEqualTo("owner");
        assertThat(asOwner.get("pending_rung").isNull()).isTrue();
        assertThat(asOwner.get("versions")).hasSize(2);
        JsonNode version = asOwner.get("version");
        assertThat(version.get("title").asText())
                .as("the version's own title, which the 版本 table shows as its note")
                .isEqualTo("pdf-tools");
        assertThat(version.get("submitted_by").asText())
                .as("a handle, not the submitter's id")
                .isEqualTo(owner);
        for (JsonNode each : asOwner.get("versions")) {
            assertThat(each.get("title").asText())
                    .as("every version carries its own title, not only the selected one")
                    .isNotBlank();
            assertThat(each.get("submitted_by").asText())
                    .as("and the handle of whoever submitted it")
                    .isEqualTo(owner);
        }

        // admin: above browse on the ladder, and stated as such rather than derived from it.
        assertThat(grant("admin").statusCode()).isEqualTo(201);
        JsonNode asAdmin = JSON.readTree(friendBrowser.get(uri(path(""))).body());
        assertThat(asAdmin.get("rung").asText()).isEqualTo("admin");

        // browse: the manifest and the body address, and no drafts.
        assertThat(visibility("public", "browse").statusCode()).isEqualTo(204);
        HttpResponse<String> strangerPage = strangerBrowser.get(uri(path("")));
        assertThat(strangerPage.statusCode()).isEqualTo(200);
        JsonNode asStranger = JSON.readTree(strangerPage.body());
        assertThat(asStranger.get("rung").asText()).isEqualTo("browse");
        assertThat(asStranger.get("files")).isNotEmpty();
        assertThat(asStranger.get("resources").isNull()).isFalse();
        assertThat(asStranger.get("version").isNull()).isFalse();
        assertThat(asStranger.get("versions")).hasSize(1);
        assertThat(asStranger.get("versions").get(0).get("state").asText())
                .as("a draft is the author's, not a reader's")
                .isEqualTo("published");

        // Their own request comes back on a reload, and goes away when they withdraw it.
        assertThat(requestAs(strangerBrowser, "write").statusCode()).isEqualTo(201);
        assertThat(JSON.readTree(strangerBrowser.get(uri(path(""))).body())
                .get("pending_rung").asText()).isEqualTo("write");
        assertThat(strangerBrowser.delete(uri(path("/access-requests/me"))).statusCode())
                .isEqualTo(204);
        JsonNode afterWithdraw = JSON.readTree(strangerBrowser.get(uri(path(""))).body());
        assertThat(afterWithdraw.get("pending_rung").isNull()).isTrue();
        assertThat(afterWithdraw.get("rung").asText())
                .as("withdrawing a request does not lower the standing it was made from")
                .isEqualTo("browse");

        // invoke: level one only — no manifest and no body address. The version is still named,
        // because that is what a caller pins the body by; only `resource`s would be an address.
        assertThat(visibility("public", "invoke").statusCode()).isEqualTo(204);
        JsonNode asInvoke = JSON.readTree(strangerBrowser.get(uri(path(""))).body());
        assertThat(asInvoke.get("rung").asText()).isEqualTo("invoke");
        assertThat(asInvoke.get("files")).isEmpty();
        assertThat(asInvoke.get("resources").isNull()).isTrue();
        assertThat(asInvoke.get("version").isNull()).isFalse();
        assertThat(asInvoke.get("versions")).hasSize(1);

        // A skill nothing was ever published from: there is no version to name, so `version` is
        // null rather than an empty object — at invoke and at browse alike.
        Multipart draftOnly =
                Multipart.create().file("file", "draft-only.zip", skill("draft-only", "1.0.0"));
        HttpResponse<String> submittedDraftOnly = send(request(SKILLS, ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, draftOnly.contentType())
                .POST(draftOnly.publisher())
                .build());
        assertThat(submittedDraftOnly.statusCode())
                .as("submit failed: %s", submittedDraftOnly.body())
                .isEqualTo(201);

        assertThat(publicRungOf("draft-only", "invoke")).isEqualTo(204);
        JsonNode draftOnlyInvoke = JSON.readTree(
                strangerBrowser.get(uri("/web/skills/" + owner + "/draft-only")).body());
        assertThat(draftOnlyInvoke.get("version").isNull())
                .as("no version has ever been live, so there is none to name")
                .isTrue();
        assertThat(draftOnlyInvoke.get("resources").isNull()).isTrue();
        assertThat(draftOnlyInvoke.get("versions")).isEmpty();

        assertThat(publicRungOf("draft-only", "browse")).isEqualTo(204);
        JsonNode draftOnlyBrowse = JSON.readTree(
                strangerBrowser.get(uri("/web/skills/" + owner + "/draft-only")).body());
        assertThat(draftOnlyBrowse.get("version").isNull())
                .as("browse may read the manifest, but there is no published version to pin")
                .isTrue();
        assertThat(draftOnlyBrowse.get("files")).isEmpty();
        assertThat(draftOnlyBrowse.get("resources").isNull())
                .as("and no address is advertised for a body that is not live")
                .isTrue();
    }

    private int publicRungOf(String name, String publicRung) {
        return browser.patch(uri("/web/skills/" + owner + "/" + name + "/visibility"),
                        json(Map.of("visibility", "public", "public_rung", publicRung)))
                .statusCode();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private String path(String suffix) {
        return "/web/skills/" + owner + "/pdf-tools" + suffix;
    }

    /** Submits v1 with a `metadata.when_to_use`, then publishes it. */
    private String published() {
        HttpResponse<String> submitted = submit(skill("pdf-tools", "1.0.0"));
        assertThat(submitted.statusCode()).as("submit failed: %s", submitted.body()).isEqualTo(201);
        JsonNode submittedBody = JSON.readTree(submitted.body());
        assertThat(webPost(path("/publish"), json(Map.of(
                "version", submittedBody.get("version").get("draft_id").asText(),
                "release_name", "1.0.0"))).statusCode())
                .isEqualTo(200);
        return JSON.readTree(submitted.body()).get("version").get("digest").asText();
    }

    private HttpResponse<String> submit(byte[] zip) {
        Multipart multipart = Multipart.create().file("file", "pdf-tools.zip", zip);
        return send(request(SKILLS, ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }

    private HttpResponse<String> grant(String role) {
        return send(request(SKILLS + "/" + owner + "/pdf-tools/grants", ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("handle", friend, "role", role))))
                .build());
    }

    private HttpResponse<String> addVersion(String token, String version) {
        Multipart multipart =
                Multipart.create().file("file", "pdf-tools.zip", skill("pdf-tools", version));
        return send(request(SKILLS + "/" + owner + "/pdf-tools/versions", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }

    private HttpResponse<String> visibility(String visibility, String publicRung) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visibility", visibility);
        if (publicRung != null) {
            body.put("public_rung", publicRung);
        }
        return browser.patch(uri(path("/visibility")), json(body));
    }

    private HttpResponse<String> requestAs(Browser who, String rung) {
        return who.post(uri(path("/access-requests")), json(Map.of("rung", rung)),
                who.csrfToken());
    }

    private HttpResponse<String> approve(String rung) {
        return webPost(path("/access-requests/" + friend + "/approve"),
                json(Map.of("rung", rung)));
    }

    private HttpResponse<String> webPostAs(Browser who, String path, String body) {
        return who.post(uri(path), body, who.csrfToken());
    }

    /**
     * A zip holding one skill, wrapped in a directory named after it.
     *
     * <p>{@code when_to_use} is under {@code metadata}, which is the key the listing reads since
     * V21; the top-level spelling is what the backfill accepts for rows written before it.
     */
    private static byte[] skill(String name, String version) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(name + "/SKILL.md",
                "---\nname: " + name + "\ndescription: extract and merge PDFs\n"
                        + "version: \"" + version + "\"\n"
                        + "metadata:\n  when_to_use: 要动 PDF 的时候用\n---\nbody\n");
        files.put(name + "/references/notes.md", "notes\n");
        return Zips.ofText(files);
    }
}
