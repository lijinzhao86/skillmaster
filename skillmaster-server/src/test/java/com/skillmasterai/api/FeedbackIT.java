package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The feedback plane (v63): the notice, the submission, its refusals, the notification, and the
 * withdrawal's ownership rule.
 */
@Sql("/sql/truncate-business-tables.sql")
class FeedbackIT extends AbstractOrganizeIT {

    private static final String PATH = "/api/v1/feedback";

    @Test
    void servesTheConsentNoticeAndItsVersion() {
        HttpResponse<String> response = get(PATH + "/consent", token());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("version").asText()).isNotBlank();
        assertThat(body.get("notice").asText())
                .contains("隐私政策")
                .contains("skmr feedback delete")
                .contains("--yes");
    }

    @Test
    void submittingStoresTheDocumentAndNotifiesItsAuthor() {
        String bearer = token();
        String version = consentVersion(bearer);
        String content = "## 我想做什么\n复现一个失败。\n\n## 发生了什么\n返回 500。\n";

        HttpResponse<String> response = postJson(PATH, bearer, JSON.writeValueAsString(
                Map.of("content", content, "consent_version", version)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        String receipt = json(response).get("receipt").asText();
        assertThat(receipt).startsWith("FB-");

        Map<String, Object> row = jdbc.sql("""
                        SELECT content, content_sha256, consent_version, user_id, source, consent_at
                        FROM user_feedback WHERE receipt = :receipt
                        """)
                .param("receipt", receipt)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "content", rs.getString("content"),
                        "sha", rs.getString("content_sha256"),
                        "consent", rs.getString("consent_version"),
                        "user", rs.getString("user_id"),
                        "source", rs.getString("source"),
                        "consent_at", rs.getString("consent_at")))
                .single();
        assertThat(row.get("content")).isEqualTo(content);
        assertThat(row.get("sha"))
                .isEqualTo(Sha256Hex.of(content.getBytes(StandardCharsets.UTF_8)));
        assertThat(row.get("consent")).isEqualTo(version);
        assertThat(row.get("user")).isEqualTo(DEMO_USER_ID);
        assertThat(row.get("source")).isEqualTo("cli");
        assertThat(java.time.Instant.parse((String) row.get("consent_at"))).isNotNull();

        assertThat(count("""
                        SELECT count(*) FROM notification_recipient r
                        JOIN notification n ON n.id = r.notification_id
                        WHERE n.kind = 'announcement' AND n.body LIKE :needle
                          AND n.body LIKE '%--yes%' AND n.body LIKE '%' || :receipt || '%'
                          AND r.user_id = :user
                        """, Map.of("needle", "%" + receipt + "%", "receipt", receipt,
                        "user", DEMO_USER_ID)))
                .as("one in-app receipt naming the receipt number and the runnable command")
                .isEqualTo(1);
        String ranged = jdbc.sql("""
                        SELECT content FROM user_feedback
                        WHERE created_at >= :from AND created_at < :to
                        """)
                .param("from", "2020-01-01T00:00:00Z")
                .param("to", "2099-01-01T00:00:00Z")
                .query(String.class)
                .single();
        assertThat(ranged).as("the operator's time-range read").isEqualTo(content);
    }

    @Test
    void emptyTooLongAndStaleConsentAreRefusedWithoutWriting() {
        String bearer = token();
        String version = consentVersion(bearer);

        assertRefused(bearer, "", version, "feedback_empty");
        assertRefused(bearer, "字".repeat(20_001), version, "feedback_too_long");
        assertRefused(bearer, "x", "0.0", "feedback_consent_stale");
        assertThat(count("SELECT count(*) FROM user_feedback")).isZero();
    }

    @Test
    void sensitiveContentNeedsAnExplicitConfirmationAndSecretsAreRedacted() {
        String bearer = token();
        String version = consentVersion(bearer);
        String content = "手机号 13800138000，密钥 sk-abcdefgh12345678";

        HttpResponse<String> refused = postJson(PATH, bearer, JSON.writeValueAsString(
                Map.of("content", content, "consent_version", version)));
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(json(refused).get("error").get("code").asText())
                .isEqualTo("feedback_sensitive");

        HttpResponse<String> accepted = postJson(PATH, bearer, JSON.writeValueAsString(
                Map.of("content", content, "consent_version", version, "allow_sensitive", true)));
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String receipt = json(accepted).get("receipt").asText();
        String stored = jdbc.sql("SELECT content FROM user_feedback WHERE receipt = :receipt")
                .param("receipt", receipt)
                .query(String.class)
                .single();
        assertThat(stored)
                .as("the phone number is the report; the key is not")
                .contains("13800138000")
                .contains("[已隐藏]")
                .doesNotContain("sk-abcdefgh");
    }

    @Test
    void aDocumentWithOnlyASecretIsStoredWithItReplaced() {
        String bearer = token();
        HttpResponse<String> response = postJson(PATH, bearer, JSON.writeValueAsString(Map.of(
                "content", "key sk-abcdefgh12345678 fails",
                "consent_version", consentVersion(bearer))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        String receipt = json(response).get("receipt").asText();
        String stored = jdbc.sql("SELECT content FROM user_feedback WHERE receipt = :r")
                .param("r", receipt)
                .query(String.class)
                .single();
        assertThat(stored).contains("[已隐藏]").doesNotContain("sk-abcdefgh");
    }

    @Test
    void theEleventhSubmissionWithinAnHourIsRefused() {
        String bearer = token();
        String version = consentVersion(bearer);
        String at = Timestamps.now();
        for (int i = 0; i < 10; i++) {
            jdbc.sql("""
                            INSERT INTO user_feedback
                                (id, receipt, user_id, content, content_sha256, consent_version,
                                 consent_at, source, created_at)
                            VALUES (:id, :receipt, :user, 'x', :sha, :version, :at, 'cli', :at)
                            """)
                    .param("id", "01M3HTG7GCVBGRPAFFSVSF12" + i)
                    .param("receipt", "FB-20260101-SEED" + i)
                    .param("user", DEMO_USER_ID)
                    .param("sha", "0".repeat(64))
                    .param("version", version)
                    .param("at", at)
                    .update();
        }
        HttpResponse<String> response = postJson(PATH, bearer, JSON.writeValueAsString(
                Map.of("content", "eleventh", "consent_version", version)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
        assertThat(json(response).get("error").get("code").asText())
                .isEqualTo("feedback_throttled");
        assertThat(count("SELECT count(*) FROM user_feedback")).isEqualTo(10);
    }

    @Test
    void deletingIsScopedToItsOwnerAndRemovesTheRow() {
        String bearer = token();
        String receipt = submit(bearer, "to withdraw");

        assertThat(delete(PATH + "/" + receipt, bearer).statusCode()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM user_feedback WHERE receipt = :receipt",
                Map.of("receipt", receipt))).isZero();
        assertThat(delete(PATH + "/" + receipt, bearer).statusCode()).isEqualTo(404);

        String otherReceipt = submit(bearer, "not yours");
        String otherId = registerAndSignIn("feedback-other", randomPhone());
        String otherBearer = tokenFor(otherId);
        HttpResponse<String> refused = delete(PATH + "/" + otherReceipt, otherBearer);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(404);
        assertThat(json(refused).get("error").get("code").asText())
                .isEqualTo("feedback_not_found");
        assertThat(count("SELECT count(*) FROM user_feedback WHERE receipt = :receipt",
                Map.of("receipt", otherReceipt))).isEqualTo(1);
    }

    @Test
    void diagnosticsKeepOnlyTheWhitelistedKeys() {
        String bearer = token();
        String version = consentVersion(bearer);
        HttpResponse<String> response = postJson(PATH, bearer, JSON.writeValueAsString(Map.of(
                "content", "diagnostics",
                "consent_version", version,
                "diagnostics", Map.of(
                        "cli_version", "1.1.0",
                        "os", "macOS",
                        "evil", "should not survive"))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        String receipt = json(response).get("receipt").asText();
        String stored = jdbc.sql("SELECT diagnostics::text FROM user_feedback WHERE receipt = :r")
                .param("r", receipt)
                .query(String.class)
                .single();
        assertThat(stored).contains("cli_version").contains("os").doesNotContain("evil");
    }

    @Test
    void diagnosticsKeepTheCommandNameOnly() {
        String bearer = token();
        HttpResponse<String> response = postJson(PATH, bearer, JSON.writeValueAsString(Map.of(
                "content", "diagnostics",
                "consent_version", consentVersion(bearer),
                "diagnostics", Map.of(
                        "command", "skmr skill invoke alice/thing --version 2",
                        "cli_version", "1.1.0"))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        String receipt = json(response).get("receipt").asText();
        String stored = jdbc.sql("SELECT diagnostics::text FROM user_feedback WHERE receipt = :r")
                .param("r", receipt)
                .query(String.class)
                .single();
        assertThat(stored).contains("skmr").doesNotContain("alice/thing");
    }

    @Test
    void aDocumentWithANulIsRefused() {
        String bearer = token();
        HttpResponse<String> response = postJson(PATH, bearer, JSON.writeValueAsString(Map.of(
                "content", "before\u0000after",
                "consent_version", consentVersion(bearer))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(json(response).get("error").get("code").asText())
                .isEqualTo("invalid_request");
    }

    private String consentVersion(String bearer) {
        return json(get(PATH + "/consent", bearer)).get("version").asText();
    }

    private String submit(String bearer, String content) {
        HttpResponse<String> response = postJson(PATH, bearer, JSON.writeValueAsString(
                Map.of("content", content, "consent_version", consentVersion(bearer))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("receipt").asText();
    }

    private void assertRefused(String bearer, String content, String version, String code) {
        HttpResponse<String> response = postJson(PATH, bearer, JSON.writeValueAsString(
                Map.of("content", content, "consent_version", version)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(json(response).get("error").get("code").asText()).isEqualTo(code);
    }

}
