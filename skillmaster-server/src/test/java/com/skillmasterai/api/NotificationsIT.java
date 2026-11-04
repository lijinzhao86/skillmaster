package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The notification floor and the two request flows built on it (v45, ADR 0139 / ADR 0140).
 *
 * <p>The rows are asserted directly where the wire cannot show them — an invitation that granted
 * something before it was accepted, or a notification that changed a business state when it was
 * only marked read, would both look fine in a 200.
 */
@Sql("/sql/truncate-business-tables.sql")
class NotificationsIT extends AbstractAccountIT {

    private static final String SKILLS = "/api/v1/skills";
    private static final String NOTIFICATIONS = "/api/v1/notifications";

    private String owner;
    private String ownerToken;
    private String invitee;
    private String inviteeToken;
    private String admin;
    private String adminToken;
    private String maintainer;
    private String maintainerToken;

    @BeforeEach
    void fourAccounts() {
        String inviteePhone = randomPhone();
        invitee = randomUsername();
        inviteeToken = tokenFor(registerAndSignIn(invitee, inviteePhone));

        String adminPhone = randomPhone();
        admin = randomUsername();
        adminToken = tokenFor(registerAndSignIn(admin, adminPhone));

        maintainer = randomUsername();
        maintainerToken = tokenFor(registerAndSignIn(maintainer, randomPhone()));

        owner = randomUsername();
        ownerToken = tokenFor(registerAndSignIn(owner, randomPhone()));
        publish();
        materializeGrant(owner, "pdf-tools", admin, "admin");
        materializeGrant(owner, "pdf-tools", maintainer, "maintain");
    }

    @Test
    void anInvitationIsNotAGrantUntilAcceptedAndReadingItIsNotHandlingIt() {
        HttpResponse<String> invited = invite("write");
        assertThat(invited.statusCode()).as(invited.body()).isEqualTo(201);
        assertThat(count("SELECT count(*) FROM skill_grant WHERE grantee_id ="
                + " (SELECT id FROM app_user WHERE handle = :handle)", Map.of("handle", invitee)))
                .isZero();
        assertThat(get(SKILLS + "/" + owner + "/pdf-tools", inviteeToken).statusCode())
                .isEqualTo(404);

        JsonNode row = pending(inviteeToken, "share_invitation");
        String id = row.get("id").asText();
        assertThat(row.get("state").asText()).isEqualTo("pending");
        assertThat(row.get("viewable").asBoolean()).isFalse();

        assertThat(send(request(NOTIFICATIONS + "/" + id + "/read", inviteeToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode()).isEqualTo(204);
        // Marking read is idempotent: the second call is the same state, not a 404.
        assertThat(send(request(NOTIFICATIONS + "/" + id + "/read", inviteeToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode()).isEqualTo(204);
        JsonNode afterRead = JSON.readTree(get(NOTIFICATIONS + "/" + id, inviteeToken).body());
        assertThat(afterRead.get("read").asBoolean()).isTrue();
        assertThat(afterRead.get("state").asText())
                .as("read is not handled: the invitation is still waiting")
                .isEqualTo("pending");
        assertThat(count("SELECT count(*) FROM skill_invitation")).isEqualTo(1);

        HttpResponse<String> accepted = send(request(NOTIFICATIONS + "/" + id + "/accept",
                inviteeToken).POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(JSON.readTree(accepted.body()).get("role").asText()).isEqualTo("write");
        assertThat(count("SELECT count(*) FROM skill_grant WHERE grantee_id ="
                + " (SELECT id FROM app_user WHERE handle = :handle)", Map.of("handle", invitee)))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM skill_invitation")).isZero();

        JsonNode archived = JSON.readTree(get(NOTIFICATIONS + "/" + id, inviteeToken).body());
        assertThat(archived.get("state").asText()).isEqualTo("accepted");
        assertThat(archived.get("viewable").asBoolean()).isTrue();
        assertThat(archived.get("read").asBoolean())
                .as("answering a notification is reading it")
                .isTrue();
        assertThat(archived.get("resolved_at").asText()).isNotBlank();
        assertThat(JSON.readTree(get(NOTIFICATIONS + "?status=pending", inviteeToken).body())
                .get("items")).isEmpty();
        assertThat(JSON.readTree(get(NOTIFICATIONS + "?status=all", inviteeToken).body())
                .get("items")).hasSize(1);
    }

    @Test
    void repeatingEitherRequestKeepsOneOpenNotification() {
        assertThat(invite("write").statusCode()).isEqualTo(201);
        assertThat(invite("browse").statusCode()).as("a second invitation moves the open one")
                .isEqualTo(201);
        assertThat(count("SELECT count(*) FROM skill_invitation")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification WHERE kind = 'share_invitation'"
                + " AND resolved_at IS NULL")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT role FROM notification WHERE kind = 'share_invitation'")
                .query(String.class).single()).isEqualTo("browse");

        assertThat(materializeBrowse()).isTrue();
        assertThat(ask("write").statusCode()).isEqualTo(201);
        assertThat(ask("maintain").statusCode()).isEqualTo(201);
        assertThat(count("SELECT count(*) FROM skill_access_request")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification WHERE kind = 'access_request'"
                + " AND resolved_at IS NULL")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT role FROM notification WHERE kind = 'access_request'"
                + " AND resolved_at IS NULL").query(String.class).single()).isEqualTo("maintain");
    }

    @Test
    void anExpiredInvitationCannotBeAcceptedAndSaysWhy() {
        assertThat(invite("write").statusCode()).isEqualTo(201);
        String id = pending(inviteeToken, "share_invitation").get("id").asText();
        String past = Timestamps.format(Instant.now().minus(31, ChronoUnit.DAYS));
        jdbc.sql("UPDATE skill_invitation SET expires_at = :past").param("past", past).update();
        jdbc.sql("UPDATE notification SET expires_at = :past").param("past", past).update();

        HttpResponse<String> accepted = send(request(NOTIFICATIONS + "/" + id + "/accept",
                inviteeToken).POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(accepted.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(accepted.body()).get("error").get("code").asText())
                .isEqualTo("invalid_notification_state");
        assertThat(JSON.readTree(accepted.body()).get("error").get("details").get(0)
                .get("issue").asText()).isEqualTo("expired");
        assertThat(count("SELECT count(*) FROM skill_invitation")).isZero();
        assertThat(JSON.readTree(get(NOTIFICATIONS + "/" + id, inviteeToken).body())
                .get("state").asText()).isEqualTo("expired");

        // Re-sharing opens a new row and a new notification; the old one stays as history.
        assertThat(invite("write").statusCode()).isEqualTo(201);
        assertThat(count("SELECT count(*) FROM notification WHERE kind = 'share_invitation'"))
                .isEqualTo(2);
    }

    @Test
    void acceptingAnOlderInvitationDoesNotLowerAHigherGrant() {
        assertThat(invite("write").statusCode()).isEqualTo(201);
        String id = pending(inviteeToken, "share_invitation").get("id").asText();
        // The invitation is older than the grant: an approval has since made this person admin.
        // Written directly because `materializeGrant` also deletes the invitation (it emulates an
        // accepted share), which is exactly the state this test needs to keep.
        jdbc.sql("""
                INSERT INTO skill_grant (skill_id, grantee_id, role, granted_by, created_at)
                SELECT s.id, invitee.id, 'admin', owner.id, :at
                  FROM skill s
                  JOIN namespace n ON n.id = s.namespace_id
                  JOIN app_user owner ON owner.id = n.owner_user_id
                  JOIN app_user invitee ON invitee.handle = :invitee
                 WHERE n.slug = :owner AND s.name = 'pdf-tools'
                """).param("at", Timestamps.now())
                .param("owner", owner)
                .param("invitee", invitee)
                .update();

        HttpResponse<String> accepted = send(request(NOTIFICATIONS + "/" + id + "/accept",
                inviteeToken).POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(JSON.readTree(accepted.body()).get("role").asText())
                .as("the higher rung wins; accepting an older invitation is not a demotion")
                .isEqualTo("admin");
        assertThat(jdbc.sql("SELECT role FROM skill_grant WHERE grantee_id ="
                + " (SELECT id FROM app_user WHERE handle = :handle)")
                .param("handle", invitee).query(String.class).single()).isEqualTo("admin");
    }

    @Test
    void acceptingAnInvitationForADeletedSkillIsRefusedAndArchivedAsRevoked() {
        assertThat(invite("write").statusCode()).isEqualTo(201);
        String id = pending(inviteeToken, "share_invitation").get("id").asText();
        assertThat(webPost("/web/skills/" + owner + "/pdf-tools/delete", "").statusCode())
                .isEqualTo(204);

        HttpResponse<String> accepted = send(request(NOTIFICATIONS + "/" + id + "/accept",
                inviteeToken).POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(409);
        assertThat(JSON.readTree(accepted.body()).get("error").get("details").get(0)
                .get("issue").asText()).isEqualTo("revoked");
        assertThat(count("SELECT count(*) FROM skill_grant WHERE grantee_id ="
                + " (SELECT id FROM app_user WHERE handle = :handle)", Map.of("handle", invitee)))
                .isZero();
        assertThat(JSON.readTree(get(NOTIFICATIONS + "/" + id, inviteeToken).body())
                .get("state").asText()).isEqualTo("revoked");
    }

    @Test
    void decliningAnExpiredInvitationArchivesItAsExpiredNotDeclined() {
        assertThat(invite("write").statusCode()).isEqualTo(201);
        String id = pending(inviteeToken, "share_invitation").get("id").asText();
        String past = Timestamps.format(Instant.now().minus(31, ChronoUnit.DAYS));
        jdbc.sql("UPDATE skill_invitation SET expires_at = :past").param("past", past).update();
        jdbc.sql("UPDATE notification SET expires_at = :past").param("past", past).update();

        assertThat(send(request(NOTIFICATIONS + "/" + id + "/decline", inviteeToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode()).isEqualTo(204);
        assertThat(JSON.readTree(get(NOTIFICATIONS + "/" + id, inviteeToken).body())
                .get("state").asText()).isEqualTo("expired");
        assertThat(JSON.readTree(get(NOTIFICATIONS + "/" + id, inviteeToken).body())
                .get("read").asBoolean()).isTrue();
    }

    @Test
    void theOwnerCannotBeInvitedAsAGrantee() {
        HttpResponse<String> invited = send(request(SKILLS + "/" + owner + "/pdf-tools/invitations",
                adminToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("handle", owner, "role", "write"))))
                .build());
        assertThat(invited.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(invited.body()).get("error").get("details").get(0)
                .get("issue").asText()).isEqualTo("already_yours");
        assertThat(count("SELECT count(*) FROM skill_invitation")).isZero();
    }

    @Test
    void anEnormousPageNumberAnswersAnEmptyPageInsteadOfFiveHundred() {
        HttpResponse<String> response = get(NOTIFICATIONS + "?p=2147483647&per=50", inviteeToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).get("items")).isEmpty();
    }

    @Test
    void theInviterSeesAnExpiredInvitationAsExpired() {
        assertThat(invite("write").statusCode()).isEqualTo(201);
        String past = Timestamps.format(Instant.now().minus(31, ChronoUnit.DAYS));
        jdbc.sql("UPDATE skill_invitation SET expires_at = :past").param("past", past).update();

        JsonNode items = JSON.readTree(get(SKILLS + "/" + owner + "/pdf-tools/invitations",
                ownerToken).body()).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("handle").asText()).isEqualTo(invitee);
        assertThat(items.get(0).get("state").asText()).isEqualTo("expired");
    }

    @Test
    void loweringAnExistingGrantIsImmediateAndRaisingNeedsAnInvitation() {
        materializeGrant(owner, "pdf-tools", invitee, "maintain");

        HttpResponse<String> lowered = send(request(SKILLS + "/" + owner + "/pdf-tools/grants/"
                        + invitee, ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json(Map.of("role", "write"))))
                .build());
        assertThat(lowered.statusCode()).as(lowered.body()).isEqualTo(200);
        assertThat(JSON.readTree(lowered.body()).get("changed").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT role FROM skill_grant WHERE grantee_id ="
                + " (SELECT id FROM app_user WHERE handle = :handle)")
                .param("handle", invitee).query(String.class).single()).isEqualTo("write");

        HttpResponse<String> raised = send(request(SKILLS + "/" + owner + "/pdf-tools/grants/"
                        + invitee, ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json(Map.of("role", "admin"))))
                .build());
        assertThat(raised.statusCode()).as(raised.body()).isEqualTo(400);
        assertThat(JSON.readTree(raised.body()).get("error").get("details").get(0)
                .get("issue").asText()).isEqualTo("raise_needs_invitation");
        assertThat(jdbc.sql("SELECT role FROM skill_grant WHERE grantee_id ="
                + " (SELECT id FROM app_user WHERE handle = :handle)")
                .param("handle", invitee).query(String.class).single())
                .as("raising must not be a direct grant")
                .isEqualTo("write");

        HttpResponse<String> noGrant = send(request(SKILLS + "/" + owner + "/pdf-tools/grants/"
                        + owner, ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json(Map.of("role", "write"))))
                .build());
        assertThat(noGrant.statusCode()).isEqualTo(404);
    }

    @Test
    void anAccessRequestReachesTheOwnerAndAdminsButNotMaintainers() {
        assertThat(materializeBrowse()).isTrue();

        assertThat(ask("write").statusCode()).isEqualTo(201);

        assertThat(pending(ownerToken, "access_request")).isNotNull();
        assertThat(pending(adminToken, "access_request")).isNotNull();
        assertThat(JSON.readTree(get(NOTIFICATIONS + "?status=pending", maintainerToken).body())
                .get("items")).isEmpty();
        assertThat(JSON.readTree(get(NOTIFICATIONS, maintainerToken).body()).get("unread").asLong())
                .isZero();
    }

    @Test
    void approvingFromTheNotificationGrantsTheRungAndClosesBothSides() {
        assertThat(materializeBrowse()).isTrue();
        assertThat(ask("browse").statusCode()).isEqualTo(400); // not above the held rung
        assertThat(ask("write").statusCode()).isEqualTo(201);

        JsonNode request = pending(ownerToken, "access_request");
        HttpResponse<String> approved = send(request(NOTIFICATIONS + "/" + request.get("id").asText()
                        + "/approve", ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("role", "write"))))
                .build());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(JSON.readTree(approved.body()).get("role").asText()).isEqualTo("write");

        assertThat(count("SELECT count(*) FROM skill_access_request")).isZero();
        assertThat(count("SELECT count(*) FROM skill_grant WHERE grantee_id ="
                + " (SELECT id FROM app_user WHERE handle = :handle) AND role = 'write'",
                Map.of("handle", invitee)))
                .as("the existing browse row moved to write; the pair is the key")
                .isEqualTo(1);

        JsonNode ownerRow = JSON.readTree(get(NOTIFICATIONS + "/" + request.get("id").asText(),
                ownerToken).body());
        assertThat(ownerRow.get("state").asText()).isEqualTo("approved");
        assertThat(ownerRow.get("read").asBoolean())
                .as("approving from the notification is reading it")
                .isTrue();
        assertThat(ownerRow.get("resolved_at").asText()).isNotBlank();

        JsonNode result = pending(inviteeToken, "access_request_result");
        assertThat(result.get("state").asText()).isEqualTo("approved");
        assertThat(result.get("role").asText()).isEqualTo("write");
    }

    @Test
    void denyingFromTheNotificationLeavesNoGrantAndTellsTheRequester() {
        assertThat(materializeBrowse()).isTrue();
        assertThat(ask("write").statusCode()).isEqualTo(201);
        JsonNode request = pending(ownerToken, "access_request");

        HttpResponse<String> denied = send(request(NOTIFICATIONS + "/" + request.get("id").asText()
                        + "/deny", ownerToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(denied.statusCode()).isEqualTo(204);

        assertThat(count("SELECT count(*) FROM skill_access_request")).isZero();
        assertThat(count("SELECT count(*) FROM skill_grant WHERE grantee_id ="
                + " (SELECT id FROM app_user WHERE handle = :handle)", Map.of("handle", invitee)))
                .as("the browse fixture only; nothing was added")
                .isEqualTo(1);

        JsonNode result = pending(inviteeToken, "access_request_result");
        assertThat(result.get("state").asText()).isEqualTo("rejected");
    }

    @Test
    void losingTheAdminRungRemovesTheRequestNotificationFromTheFeedAndTheByIdRead() {
        assertThat(materializeBrowse()).isTrue();
        assertThat(ask("write").statusCode()).isEqualTo(201);
        String id = pending(adminToken, "access_request").get("id").asText();

        jdbc.sql("UPDATE skill_grant SET role = 'maintain' WHERE grantee_id ="
                + " (SELECT id FROM app_user WHERE handle = :handle)")
                .param("handle", admin)
                .update();

        assertThat(JSON.readTree(get(NOTIFICATIONS + "?status=pending", adminToken).body())
                .get("items")).isEmpty();
        assertThat(get(NOTIFICATIONS + "/" + id, adminToken).statusCode()).isEqualTo(404);
    }

    @Test
    void aBroadcastIsOneBodyAndPerRecipientReadState() {
        String at = Timestamps.now();
        String id = "broadcast-1";
        jdbc.sql("""
                INSERT INTO notification (id, kind, title, body, created_at)
                VALUES (:id, 'announcement', '平台维护', '11 月 9 日维护', :at)
                """).param("id", id).param("at", at).update();
        jdbc.sql("""
                INSERT INTO notification_recipient (notification_id, user_id, created_at)
                SELECT :id, id, :at FROM app_user WHERE handle = ANY(:handles)
                """).param("id", id).param("at", at)
                .param("handles", List.of(invitee, admin).toArray(String[]::new)).update();

        assertThat(count("SELECT count(*) FROM notification WHERE id = :id", Map.of("id", id)))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification_recipient WHERE notification_id = :id",
                Map.of("id", id))).isEqualTo(2);

        assertThat(send(request(NOTIFICATIONS + "/" + id + "/read", inviteeToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode()).isEqualTo(204);
        assertThat(JSON.readTree(get(NOTIFICATIONS, inviteeToken).body()).get("unread").asLong())
                .isZero();
        assertThat(JSON.readTree(get(NOTIFICATIONS + "?status=pending", inviteeToken).body())
                .get("items"))
                .as("an announcement is information, never work")
                .isEmpty();
        assertThat(JSON.readTree(get(NOTIFICATIONS + "?status=all", inviteeToken).body())
                .get("items").get(0).get("state").asText()).isEqualTo("info");
    }

    // ------------------------------------------------------------------ helpers

    private boolean materializeBrowse() {
        materializeGrant(owner, "pdf-tools", invitee, "browse");
        return true;
    }

    private HttpResponse<String> invite(String role) {
        return send(request(SKILLS + "/" + owner + "/pdf-tools/invitations", ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("handle", invitee, "role", role))))
                .build());
    }

    private HttpResponse<String> ask(String rung) {
        return send(request(SKILLS + "/" + owner + "/pdf-tools/access-requests", inviteeToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("rung", rung))))
                .build());
    }

    /** The newest notification of a kind, from the unfiltered feed. */
    private JsonNode pending(String token, String kind) {
        HttpResponse<String> response = get(NOTIFICATIONS + "?status=all&per=50", token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        for (JsonNode row : JSON.readTree(response.body()).get("items")) {
            if (kind.equals(row.get("kind").asText())) {
                return row;
            }
        }
        throw new AssertionError("no " + kind + " notification for this account");
    }

    private void publish() {
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(Map.of("SKILL.md",
                        "---\nname: pdf-tools\ndescription: d\n---\n")))
                .field("name", "第一版");
        HttpResponse<String> submitted = send(request(SKILLS, ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(201);
        String draft = JSON.readTree(submitted.body()).get("version").get("draft_id").asText();
        HttpResponse<String> published = webPost(
                "/web/skills/" + owner + "/pdf-tools/publish",
                json(Map.of("version", draft, "release_name", "1.0.0")));
        assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
    }
}
