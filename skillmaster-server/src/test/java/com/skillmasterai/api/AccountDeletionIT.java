package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.account.AccountRegistrar;
import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Browser;
import com.skillmasterai.usecase.PurgeAccountsUseCase;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * v56: registration's consent record, immediate account deletion, and the purge thirty days later.
 *
 * <p>Every assertion is about a row or a response a client could observe. The retention window is
 * moved by rewriting {@code deleted_at} rather than by waiting — the window is a column, so the
 * test moves it.
 */
class AccountDeletionIT extends AbstractAccountIT {

    private static final String DELETE_ACCOUNT = "/web/account/delete";

    @Autowired
    private PurgeAccountsUseCase purge;

    @Autowired
    private AccountDirectory accounts;

    @Autowired
    private AccountRegistrar accountRegistrar;

    @Autowired
    private com.skillmasterai.usecase.DeleteAccountUseCase deleteAccount;

    @Test
    void registrationRefusesWithoutConsentAndSpendsNothing() {
        String phone = randomPhone();
        String code = requestCode(phone);

        HttpResponse<String> refused = webPost(REGISTER, json(Map.of(
                "phone", phone, "code", code, "password", PASSWORD,
                "username", randomUsername(), "consent", false)));

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(body(refused).get("error").get("details").get(0).get("field").asText())
                .isEqualTo("consent");

        // An absent field is the same refusal, not a deserialization error: callers that predate
        // the checkbox must get a field error, never a 500.
        HttpResponse<String> missing = webPost(REGISTER, json(Map.of(
                "phone", phone, "code", code, "password", PASSWORD,
                "username", randomUsername())));
        assertThat(missing.statusCode()).as(missing.body()).isEqualTo(400);
        assertThat(body(missing).get("error").get("details").get(0).get("field").asText())
                .isEqualTo("consent");

        // The code was not spent: the same one still registers after the checkbox is ticked.
        assertThat(webPost(REGISTER, json(Map.of(
                "phone", phone, "code", code, "password", PASSWORD,
                "username", randomUsername(), "consent", true))).statusCode()).isEqualTo(201);
    }

    @Test
    void registrationRecordsTheVersionItServed() {
        String userId = registerAndSignIn(randomUsername(), randomPhone());

        Map<String, Object> row = jdbc.sql("SELECT terms_version, privacy_version FROM legal_consent"
                        + " WHERE user_id = :id")
                .param("id", userId)
                .query((rs, n) -> Map.<String, Object>of(
                        "terms", rs.getString("terms_version"),
                        "privacy", rs.getString("privacy_version")))
                .single();
        // The server's configured values, not anything the client could have sent.
        assertThat(row.get("terms")).isEqualTo(properties.account().termsVersion());
        assertThat(row.get("privacy")).isEqualTo(properties.account().privacyVersion());
    }

    @Test
    void aWrongUsernameOrPasswordDeletesNothing() {
        String userId = registerAndSignIn(randomUsername(), randomPhone());
        String handle = handleOf(userId);

        HttpResponse<String> wrongName = webPost(DELETE_ACCOUNT, json(Map.of(
                "username", "not-" + handle, "password", PASSWORD)));
        HttpResponse<String> wrongPassword = webPost(DELETE_ACCOUNT, json(Map.of(
                "username", handle, "password", "wrong-horse-battery")));

        assertThat(wrongName.statusCode()).isEqualTo(401);
        assertThat(body(wrongName).get("error").get("code").asText())
                .isEqualTo("invalid_credentials");
        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(body(wrongPassword).get("error").get("code").asText())
                .isEqualTo("invalid_credentials");
        assertThat(statusOf(userId)).isEqualTo("active");
        // Still signed in: a failed attempt changes nothing, including the session.
        assertThat(webGet(SESSION).statusCode()).isEqualTo(200);
    }

    @Test
    void deletingStopsTheAccountAtOnceAndEndsItsSessions() {
        String userId = registerAndSignIn(randomUsername(), randomPhone());
        String handle = handleOf(userId);

        HttpResponse<String> deleted = webPost(DELETE_ACCOUNT, json(Map.of(
                "username", handle, "password", PASSWORD)));

        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
        assertThat(statusOf(userId)).isEqualTo("deleted");
        assertThat(jdbc.sql("SELECT deleted_at FROM app_user WHERE id = :id")
                .param("id", userId).query(String.class).single()).isNotNull();
        // The session that made the request is gone too, not just every other one.
        assertThat(webGet(SESSION).statusCode()).isEqualTo(401);
    }

    @Test
    void loggingInAgainSaysTheAccountWasDeletedOnlyWhenThePasswordIsRight() {
        String phone = randomPhone();
        String userId = registerAndSignIn(randomUsername(), phone);
        String handle = handleOf(userId);
        webPost(DELETE_ACCOUNT, json(Map.of("username", handle, "password", PASSWORD)));

        HttpResponse<String> right = webPost(LOGIN, json(Map.of(
                "phone", phone, "password", PASSWORD)));
        HttpResponse<String> wrong = webPost(LOGIN, json(Map.of(
                "phone", phone, "password", "wrong-horse-battery")));

        assertThat(right.statusCode()).isEqualTo(401);
        assertThat(body(right).get("error").get("code").asText()).isEqualTo("account_deleted");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(body(wrong).get("error").get("code").asText())
                .isEqualTo("invalid_credentials");
    }

    @Test
    void deletingRevokesEveryApiKeyAndEndsEverySession() {
        String phone = randomPhone();
        String userId = registerAndSignIn(randomUsername(), phone);
        String handle = handleOf(userId);
        JsonNode issued = body(webPost(KEYS, json(Map.of(
                "name", "ci", "scopes", List.of("skills:read")))));
        String token = issued.get("token").asText();
        Browser second = secondSignedInBrowser(phone, PASSWORD);

        webPost(DELETE_ACCOUNT, json(Map.of("username", handle, "password", PASSWORD)));

        assertThat(get("/api/v1/skills?q=nothing", token).statusCode())
                .as("a key issued before the deletion is refused after it")
                .isEqualTo(401);
        assertThat(second.get(uri(SESSION)).statusCode())
                .as("the other browser session is gone too, not only the one that asked")
                .isEqualTo(401);
        assertThat(jdbc.sql("SELECT count(*) FROM api_key WHERE user_id = :id AND revoked_at IS NULL")
                .param("id", userId).query(Long.class).single()).isZero();
    }

    @Test
    void theWindowStillHoldsTheAccountAndItsName() {
        String phone = randomPhone();
        String username = randomUsername();
        String userId = registerAndSignIn(username, phone);
        webPost(DELETE_ACCOUNT, json(Map.of("username", handleOf(userId), "password", PASSWORD)));

        // `AccountRegistrar` is the guard itself: calling it directly asserts what re-registration
        // would hit, without spending an SMS on a suite that has a per-address budget.
        assertThatThrownBy(() -> accountRegistrar.register(username, phone, PASSWORD))
                .isInstanceOf(com.skillmasterai.modules.account.AccountRequestException.class)
                .hasMessageContaining("username");
        // A fresh name reaches the phone check: the number itself is still held by the kept row.
        assertThatThrownBy(() -> accountRegistrar.register(randomUsername(), phone, PASSWORD))
                .isInstanceOf(com.skillmasterai.modules.account.AccountRequestException.class)
                .hasMessageContaining("phone");
    }

    @Test
    void aSecondDeletionDoesNotSendTheNoticesAgain() {
        String ownerId = registerAndSignIn(randomUsername(), randomPhone());
        String ownerHandle = handleOf(ownerId);
        String skillId = seedSkill(ownerId, "idempotent-case");
        String granteeId = seedOtherAccount("grantee-" + randomUsername());
        seedGrant(skillId, granteeId, ownerId);

        webPost(DELETE_ACCOUNT, json(Map.of("username", ownerHandle, "password", PASSWORD)));
        // The same credentials again — what a second tab or a retried request sends. The status
        // transition already happened, so the second call must not redo the side effects.
        boolean second = deleteAccount.delete(ownerId,
                new com.skillmasterai.usecase.DeleteAccountUseCase.Request(ownerHandle, PASSWORD));

        assertThat(second).isTrue();
        assertThat(count("SELECT count(*) FROM notification_recipient r"
                + " JOIN notification n ON n.id = r.notification_id"
                + " WHERE n.skill_id = :skill AND r.user_id = :user AND n.kind = 'skill_unavailable'",
                Map.of("skill", skillId, "user", granteeId))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event"
                + " WHERE actor_user_id = :id AND action = 'account_delete'",
                Map.of("id", ownerId))).isEqualTo(1);
    }

    @Test
    void thePurgeDoesNotSelectTheSameAccountTwice() {
        String userId = registerAndSignIn(randomUsername(), randomPhone());
        webPost(DELETE_ACCOUNT, json(Map.of("username", handleOf(userId), "password", PASSWORD)));
        String cutoff = com.skillmasterai.common.Timestamps.now();

        purge.purgeOne(userId);
        // A second instance — or a retry — must find the claim taken and do nothing.
        purge.purgeOne(userId);

        assertThat(count("SELECT count(*) FROM audit_event"
                + " WHERE actor_user_id = :id AND action = 'account_purge'",
                Map.of("id", userId))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM app_user"
                + " WHERE id = :id AND status = 'deleted' AND deleted_at <= :cutoff"
                + " AND purged_at IS NULL", Map.of("id", userId, "cutoff", cutoff)))
                .as("a purged account must not be due again — otherwise every day re-runs the "
                        + "global blob sweep and writes another audit row")
                .isZero();
    }

    @Test
    void aContributionInSomebodyElsesNamespaceSurvivesAndReadsAsADeletedAccount() {
        String ownerId = registerAndSignIn(randomUsername(), randomPhone());
        String ownerHandle = handleOf(ownerId);
        // Today an ordinary user cannot publish into another namespace (that is the TODO for
        // namespace-member write), so the fixture writes the rows the future path will write: a
        // namespace **owned by somebody else**, A as a member, a skill A created, and a version.
        String teamOwnerId = seedOtherAccount("team-owner-" + randomUsername());
        String teamNamespaceId = seedNamespace("team-" + randomUsername(), teamOwnerId);
        jdbc.sql("INSERT INTO namespace_member (namespace_id, user_id, role, added_at)"
                        + " VALUES (:ns, :user, 'editor', :at)")
                .param("ns", teamNamespaceId).param("user", ownerId)
                .param("at", com.skillmasterai.common.Timestamps.now()).update();
        String contribution = seedSkillIn(teamNamespaceId, ownerId, "contribution");
        seedVersion(contribution, ownerId);

        webPost(DELETE_ACCOUNT, json(Map.of("username", ownerHandle, "password", PASSWORD)));
        purge.purgeOne(ownerId);

        assertThat(count("SELECT count(*) FROM skill WHERE id = :id",
                Map.of("id", contribution))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM skill_version WHERE skill_id = :id",
                Map.of("id", contribution))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM namespace WHERE id = :id",
                Map.of("id", teamNamespaceId))).isEqualTo(1);
        // A's membership goes; the namespace and its content belong to somebody else.
        assertThat(count("SELECT count(*) FROM namespace_member"
                + " WHERE namespace_id = :ns AND user_id = :user",
                Map.of("ns", teamNamespaceId, "user", ownerId))).isZero();
        assertThat(accounts.handlesOf(List.of(ownerId))).containsEntry(ownerId, "已注销账号");
    }

    @Test
    void skillsGoInvisibleAndEveryoneUsingThemIsTold() {
        String ownerId = registerAndSignIn(randomUsername(), randomPhone());
        String ownerHandle = handleOf(ownerId);
        String skillId = seedSkill(ownerId, "deletion-case");

        // Two people outside the namespace: one shared with, one who put it in their library.
        String granteeId = seedOtherAccount("grantee-" + randomUsername());
        String joinerId = seedOtherAccount("joiner-" + randomUsername());
        seedGrant(skillId, granteeId, ownerId);
        seedLibraryJoin(skillId, joinerId);

        HttpResponse<String> deleted = webPost(DELETE_ACCOUNT, json(Map.of(
                "username", ownerHandle, "password", PASSWORD)));

        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT deleted_at FROM skill WHERE id = :id")
                .param("id", skillId).query(String.class).optional())
                .as("the skill is immediately invisible, but the row is kept for the window")
                .hasValueSatisfying(value -> assertThat(value).isNotNull());
        assertThat(recipientsOfNotice(skillId))
                .containsExactlyInAnyOrder(granteeId, joinerId);
    }

    @Test
    void thePurgeDeletesTheKeptDataAndDeidentifiesTheRow() {
        String phone = randomPhone();
        String ownerId = registerAndSignIn(randomUsername(), phone);
        String ownerHandle = handleOf(ownerId);
        String skillId = seedSkill(ownerId, "purge-case");
        // Rows in somebody else's namespace: the cascade from the personal namespace never sees
        // them, and they are personal data all the same.
        String demoNamespace = jdbc.sql("SELECT id FROM namespace WHERE slug = 'demo'")
                .query(String.class).single();
        String foreignSkill = seedSkillIn(demoNamespace, SUBJECT_USER_ID, "foreign-" + randomUsername());
        seedGrant(foreignSkill, ownerId, SUBJECT_USER_ID);
        seedLibraryJoin(foreignSkill, ownerId);
        jdbc.sql("INSERT INTO skill_invitation (skill_id, invitee_id, role, invited_by, created_at,"
                        + " expires_at) VALUES (:skill, :invitee, 'invoke', :by, :at, :at)")
                .param("skill", foreignSkill).param("invitee", ownerId)
                .param("by", SUBJECT_USER_ID)
                .param("at", com.skillmasterai.common.Timestamps.now()).update();
        jdbc.sql("INSERT INTO skill_access_request (skill_id, requester_id, wanted_role, created_at)"
                        + " VALUES (:skill, :requester, 'invoke', :at)")
                .param("skill", foreignSkill).param("requester", ownerId)
                .param("at", com.skillmasterai.common.Timestamps.now()).update();
        jdbc.sql("INSERT INTO my_tag_definition (id, user_id, name, name_key, created_at, updated_at)"
                        + " VALUES (:id, :user, 'mine', 'mine', :at, :at)")
                .param("id", Ulid.generate()).param("user", ownerId)
                .param("at", com.skillmasterai.common.Timestamps.now()).update();
        jdbc.sql("INSERT INTO skill_usage_day (skill_id, day, account_id)"
                        + " VALUES (:skill, '2026-11-09', :account)")
                .param("skill", foreignSkill).param("account", ownerId).update();

        webPost(DELETE_ACCOUNT, json(Map.of("username", ownerHandle, "password", PASSWORD)));

        purge.purgeOne(ownerId);

        assertThat(count("SELECT count(*) FROM namespace WHERE owner_user_id = :id",
                Map.of("id", ownerId))).isZero();
        assertThat(count("SELECT count(*) FROM skill WHERE id = :id", Map.of("id", skillId)))
                .isZero();
        assertThat(count("SELECT count(*) FROM credential WHERE user_id = :id",
                Map.of("id", ownerId))).isZero();
        assertThat(count("SELECT count(*) FROM legal_consent WHERE user_id = :id",
                Map.of("id", ownerId))).isZero();
        // The rows in other people's namespaces.
        assertThat(count("SELECT count(*) FROM skill_grant WHERE grantee_id = :id",
                Map.of("id", ownerId))).isZero();
        assertThat(count("SELECT count(*) FROM skill_library_join WHERE user_id = :id",
                Map.of("id", ownerId))).isZero();
        assertThat(count("SELECT count(*) FROM skill_invitation WHERE invitee_id = :id",
                Map.of("id", ownerId))).isZero();
        assertThat(count("SELECT count(*) FROM skill_access_request WHERE requester_id = :id",
                Map.of("id", ownerId))).isZero();
        assertThat(count("SELECT count(*) FROM my_tag_definition WHERE user_id = :id",
                Map.of("id", ownerId))).isZero();
        assertThat(count("SELECT count(*) FROM skill_usage_day WHERE account_id = :id",
                Map.of("id", ownerId))).isZero();
        // The account row survives, de-identified: no phone, a placeholder handle, a display name
        // other people's content can render (ADR 0175 §决定 3).
        Map<String, Object> row = jdbc.sql("SELECT handle, display_name, phone_hash, phone_enc,"
                        + " status FROM app_user WHERE id = :id")
                .param("id", ownerId)
                .query((rs, n) -> Map.<String, Object>of(
                        "handle", rs.getString("handle"),
                        "display", rs.getString("display_name"),
                        "phoneHash", String.valueOf(rs.getString("phone_hash")),
                        "phoneEnc", String.valueOf(rs.getString("phone_enc")),
                        "status", rs.getString("status")))
                .single();
        assertThat(row.get("handle")).isEqualTo("deleted-" + ownerId);
        assertThat(row.get("display")).isEqualTo("已注销账号");
        assertThat(row.get("phoneHash")).isEqualTo("null");
        assertThat(row.get("phoneEnc")).isEqualTo("null");
        assertThat(row.get("status")).isEqualTo("deleted");
        assertThat(count("SELECT count(*) FROM app_user WHERE id = :id AND purged_at IS NOT NULL"
                + " AND email IS NULL", Map.of("id", ownerId))).isEqualTo(1);
        // And the phone is free again the moment the purge has run: nothing in app_user still
        // points at it. (Asked of the rows rather than by registering again — the test client has
        // spent its SMS budget by now, and the threshold is not what this case is about.)
        assertThat(count("SELECT count(*) FROM app_user WHERE phone_hash = :hash",
                Map.of("hash", phoneCipher.hash(phone)))).isZero();
    }

    // ---- arrangement ---------------------------------------------------------------------------

    private String handleOf(String userId) {
        return jdbc.sql("SELECT handle FROM app_user WHERE id = :id")
                .param("id", userId).query(String.class).single();
    }

    private String statusOf(String userId) {
        return jdbc.sql("SELECT status FROM app_user WHERE id = :id")
                .param("id", userId).query(String.class).single();
    }

    private String seedNamespace(String slug, String ownerId) {
        String id = Ulid.generate();
        jdbc.sql("INSERT INTO namespace (id, slug, title, owner_user_id, visibility, created_at)"
                        + " VALUES (:id, :slug, :slug, :owner, 'private', :at)")
                .param("id", id)
                .param("slug", slug)
                .param("owner", ownerId)
                .param("at", com.skillmasterai.common.Timestamps.now())
                .update();
        return id;
    }

    private String seedSkillIn(String namespaceId, String ownerId, String name) {
        String skillId = Ulid.generate();
        String at = com.skillmasterai.common.Timestamps.now();
        jdbc.sql("INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,"
                        + " visibility, created_by, created_at, updated_at)"
                        + " VALUES (:id, :ns, :name, :name, '', '{}', 'private', :owner, :at, :at)")
                .param("id", skillId)
                .param("ns", namespaceId)
                .param("name", name)
                .param("owner", ownerId)
                .param("at", at)
                .update();
        return skillId;
    }

    private void seedVersion(String skillId, String publisherId) {
        jdbc.sql("INSERT INTO skill_version (id, skill_id, number, digest, file_count, total_bytes,"
                        + " submitted_by, submitted_at)"
                        + " VALUES (:id, :skill, 1, :digest, 1, 10, :publisher, :at)")
                .param("id", Ulid.generate()).param("skill", skillId)
                .param("digest", "sha256:" + Ulid.generate())
                .param("publisher", publisherId)
                .param("at", com.skillmasterai.common.Timestamps.now())
                .update();
    }

    private String seedSkill(String ownerId, String name) {
        String namespaceId = jdbc.sql("SELECT id FROM namespace WHERE owner_user_id = :id")
                .param("id", ownerId).query(String.class).single();
        String skillId = Ulid.generate();
        String at = com.skillmasterai.common.Timestamps.now();
        jdbc.sql("INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,"
                        + " visibility, created_by, created_at, updated_at)"
                        + " VALUES (:id, :ns, :name, :name, '', '{}', 'private', :owner, :at, :at)")
                .param("id", skillId)
                .param("ns", namespaceId)
                .param("name", name)
                .param("owner", ownerId)
                .param("at", at)
                .update();
        return skillId;
    }

    private String seedOtherAccount(String handle) {
        String userId = Ulid.generate();
        String phone = randomPhone();
        String at = com.skillmasterai.common.Timestamps.now();
        jdbc.sql("INSERT INTO app_user (id, handle, phone_hash, phone_enc, display_name, status,"
                        + " created_at) VALUES (:id, :handle, :hash, :enc, '', 'active', :at)")
                .param("id", userId)
                .param("handle", handle)
                .param("hash", phoneCipher.hash(phone))
                .param("enc", phoneCipher.encrypt(phone))
                .param("at", at)
                .update();
        return userId;
    }

    private void seedGrant(String skillId, String granteeId, String ownerId) {
        jdbc.sql("INSERT INTO skill_grant (skill_id, grantee_id, role, granted_by, created_at)"
                        + " VALUES (:skill, :grantee, 'invoke', :owner, :at)")
                .param("skill", skillId)
                .param("grantee", granteeId)
                .param("owner", ownerId)
                .param("at", com.skillmasterai.common.Timestamps.now())
                .update();
    }

    private void seedLibraryJoin(String skillId, String userId) {
        jdbc.sql("INSERT INTO skill_library_join (user_id, skill_id, created_at)"
                        + " VALUES (:user, :skill, :at)")
                .param("user", userId)
                .param("skill", skillId)
                .param("at", com.skillmasterai.common.Timestamps.now())
                .update();
    }

    private java.util.List<String> recipientsOfNotice(String skillId) {
        return jdbc.sql("SELECT r.user_id FROM notification_recipient r"
                        + " JOIN notification n ON n.id = r.notification_id"
                        + " WHERE n.skill_id = :skill AND n.kind = 'skill_unavailable'")
                .param("skill", skillId)
                .query(String.class)
                .list();
    }

}
