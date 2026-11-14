package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.feedback.FeedbackAttachmentSafety;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.usecase.SubmitFeedbackUseCase;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The feedback plane (v64): the notice, the submission, its refusals, the notification, and the
 * withdrawal's ownership rule.
 */
@Sql("/sql/truncate-business-tables.sql")
@Import(FeedbackIT.RecordingBlobs.class)
class FeedbackIT extends AbstractOrganizeIT {

    private static final String PATH = "/api/v1/feedback";

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");

    @Autowired
    private BlobStore blobs;

    @Autowired
    private SubmitFeedbackUseCase submit;

    @BeforeEach
    void resetBlobStore() {
        // The recorder is @Primary and shared by the class; a test that makes it fail must not
        // leak that stubbing into the next one.
        Mockito.reset(blobs);
        Mockito.doNothing().when(blobs).putObject(anyString(), any(Path.class), anyLong());
        Mockito.doNothing().when(blobs).deleteObject(anyString());
    }

    @Test
    void servesTheConsentNoticeAndItsVersion() {
        HttpResponse<String> response = get(PATH + "/consent", token());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("version").asText()).isNotBlank();
        assertThat(body.get("notice").asText())
                .contains("隐私政策")
                .contains("skmr feedback delete")
                .contains("--yes")
                .contains("附件")
                .contains("不会被自动扫描");
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

    @Test
    void submittingAttachmentsStoresRowsAndObjects() throws IOException {
        String bearer = token();
        String version = consentVersion(bearer);
        Map<String, byte[]> written = new LinkedHashMap<>();
        Mockito.doAnswer(invocation -> {
            written.put(invocation.getArgument(0),
                    Files.readAllBytes(invocation.getArgument(1)));
            return null;
        }).when(blobs).putObject(anyString(), any(Path.class), anyLong());
        Multipart form = Multipart.create().withoutName()
                .field("content", "截图见附件")
                .field("consent_version", version)
                .file("attachment", "shot.png", PNG)
                .file("attachment", "clip.mp4", mp4());

        HttpResponse<String> response = postMultipart(bearer, form);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = json(response);
        assertThat(body.get("attachments")).hasSize(2);
        assertThat(body.get("attachments").get(0).get("media_type").asText())
                .isEqualTo("image/png");
        assertThat(body.get("attachments").get(1).get("media_type").asText())
                .isEqualTo("video/mp4");
        String receipt = body.get("receipt").asText();

        String feedbackId = jdbc.sql("SELECT id FROM user_feedback WHERE receipt = :r")
                .param("r", receipt)
                .query(String.class)
                .single();
        var rows = jdbc.sql("""
                        SELECT position, filename, media_type, size_bytes, sha256, object_key
                        FROM feedback_attachment WHERE feedback_id = :id ORDER BY position
                        """)
                .param("id", feedbackId)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "position", rs.getInt("position"),
                        "filename", rs.getString("filename"),
                        "media_type", rs.getString("media_type"),
                        "size_bytes", rs.getLong("size_bytes"),
                        "sha256", rs.getString("sha256"),
                        "object_key", rs.getString("object_key")))
                .list();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("position", 0)
                .containsEntry("filename", "shot.png")
                .containsEntry("media_type", "image/png")
                .containsEntry("size_bytes", (long) PNG.length)
                .containsEntry("sha256", Sha256Hex.of(PNG));
        assertThat(rows.get(1).get("media_type")).isEqualTo("video/mp4");
        assertThat(rows.get(0).get("object_key").toString())
                .startsWith("feedback/attachments/");

        // **The key, the bytes and the digest are one contract.** The row says which bytes its key
        // names; a wiring mistake that writes one file under every key would otherwise pass.
        assertThat(written).hasSize(2);
        for (Map<String, Object> row : rows) {
            byte[] stored = written.get(row.get("object_key").toString());
            assertThat(stored).as("bytes for " + row.get("object_key")).isNotNull();
            assertThat(stored.length).isEqualTo(((Number) row.get("size_bytes")).longValue());
            assertThat(Sha256Hex.of(stored)).isEqualTo(row.get("sha256"));
        }

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(blobs, times(2)).putObject(keys.capture(), any(Path.class), anyLong());
        assertThat(keys.getAllValues()).allMatch(key -> key.startsWith("feedback/attachments/"));

        assertThat(count("""
                        SELECT count(*) FROM notification_recipient r
                        JOIN notification n ON n.id = r.notification_id
                        WHERE n.kind = 'announcement' AND n.body LIKE '%2 个附件%'
                          AND r.user_id = :user
                        """, Map.of("user", DEMO_USER_ID)))
                .as("the receipt names how many attachments arrived")
                .isEqualTo(1);
        assertThat(count("""
                        SELECT count(*) FROM audit_event
                        WHERE action = 'feedback_submit' AND actor_user_id = :user
                          AND detail::jsonb->>'attachments' = '2'
                        """, Map.of("user", DEMO_USER_ID)))
                .as("one audit row naming the attachment count")
                .isEqualTo(1);
    }

    @Test
    void aChunkedMultipartSubmissionIsAccepted() {
        String bearer = token();
        Multipart form = Multipart.create().withoutName()
                .field("content", "chunked upload")
                .field("consent_version", consentVersion(bearer))
                .file("attachment", "shot.png", PNG);
        HttpRequest request = request(PATH, bearer)
                .header(HttpHeaders.CONTENT_TYPE, form.contentType())
                .POST(form.streamingPublisher())
                .build();

        HttpResponse<String> response = send(request);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(json(response).get("attachments")).hasSize(1);
        assertThat(count("SELECT count(*) FROM feedback_attachment")).isEqualTo(1);
    }

    @Test
    void anSvgNamedPngIsRefusedBeforeStoringAnything() {
        String bearer = token();
        Multipart form = Multipart.create().withoutName()
                .field("content", "fake")
                .field("consent_version", consentVersion(bearer))
                .file("attachment", "not-a-png.png",
                        "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = postMultipart(bearer, form);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(json(response).get("error").get("code").asText())
                .isEqualTo("feedback_attachment_type");
        assertThat(count("SELECT count(*) FROM user_feedback")).isZero();
        verify(blobs, never()).putObject(anyString(), any(Path.class), anyLong());
    }

    @Test
    void sixAttachmentsAreRefused() {
        String bearer = token();
        Multipart form = Multipart.create().withoutName()
                .field("content", "too many")
                .field("consent_version", consentVersion(bearer));
        for (int i = 0; i < 6; i++) {
            form.file("attachment", "shot" + i + ".png", PNG);
        }

        HttpResponse<String> response = postMultipart(bearer, form);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(json(response).get("error").get("code").asText())
                .isEqualTo("feedback_attachment_too_many");
        assertThat(count("SELECT count(*) FROM user_feedback")).isZero();
        verify(blobs, never()).putObject(anyString(), any(Path.class), anyLong());
    }

    @Test
    void anOversizedImageIsRefused() {
        String bearer = token();
        byte[] oversized = new byte[(int) FeedbackAttachmentSafety.MAX_IMAGE_BYTES + 1];
        System.arraycopy(PNG, 0, oversized, 0, PNG.length);
        Multipart form = Multipart.create().withoutName()
                .field("content", "too large")
                .field("consent_version", consentVersion(bearer))
                .file("attachment", "big.png", oversized);

        HttpResponse<String> response = postMultipart(bearer, form);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(json(response).get("error").get("code").asText())
                .isEqualTo("feedback_attachment_too_large");
        assertThat(count("SELECT count(*) FROM user_feedback")).isZero();
        verify(blobs, never()).putObject(anyString(), any(Path.class), anyLong());
    }

    @Test
    void withdrawingDeletesEveryAttachmentObjectAndTheRows() {
        String bearer = token();
        Multipart form = Multipart.create().withoutName()
                .field("content", "withdraw attachments")
                .field("consent_version", consentVersion(bearer))
                .file("attachment", "one.png", PNG)
                .file("attachment", "two.mp4", mp4());
        HttpResponse<String> submitted = postMultipart(bearer, form);
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(201);
        String receipt = json(submitted).get("receipt").asText();
        String archiveKey = "feedback/archive/2026/11/13/01JKMNPQRSTVWXYZ0123456789.ndjson.zst";
        jdbc.sql("UPDATE user_feedback SET archive_key = :key WHERE receipt = :receipt")
                .param("key", archiveKey)
                .param("receipt", receipt)
                .update();
        Mockito.clearInvocations(blobs);

        assertThat(delete(PATH + "/" + receipt, bearer).statusCode()).isEqualTo(204);
        ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
        verify(blobs, times(3)).deleteObject(deleted.capture());
        assertThat(deleted.getAllValues())
                .as("two attachment objects and the archive copy")
                .contains(archiveKey)
                .allMatch(key -> key.startsWith("feedback/"));
        assertThat(deleted.getAllValues().get(2))
                .as("every attachment object goes before the archive copy")
                .isEqualTo(archiveKey);
        assertThat(count("SELECT count(*) FROM feedback_attachment")).isZero();
        assertThat(count("SELECT count(*) FROM user_feedback")).isZero();
        assertThat(delete(PATH + "/" + receipt, bearer).statusCode())
                .as("the second withdrawal finds no row")
                .isEqualTo(404);
    }

    @Test
    void anUnavailableBlobStoreIsA503AndLeavesNoRow() {
        Mockito.doThrow(new IllegalStateException("bucket down"))
                .when(blobs).putObject(anyString(), any(Path.class), anyLong());
        String bearer = token();
        Multipart form = Multipart.create().withoutName()
                .field("content", "storage down")
                .field("consent_version", consentVersion(bearer))
                .file("attachment", "shot.png", PNG);

        HttpResponse<String> response = postMultipart(bearer, form);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(json(response).get("error").get("code").asText())
                .isEqualTo("feedback_attachment_unavailable");
        assertThat(count("SELECT count(*) FROM user_feedback")).isZero();
        // The key is recorded before the PUT, so the failed write is cleaned up too.
        verify(blobs, times(1)).deleteObject(anyString());
    }

    @Test
    void aFailedSecondUploadRemovesTheFirstObjectToo() {
        Mockito.doNothing()
                .doThrow(new IllegalStateException("second fails"))
                .when(blobs).putObject(anyString(), any(Path.class), anyLong());
        String bearer = token();
        Multipart form = Multipart.create().withoutName()
                .field("content", "second upload fails")
                .field("consent_version", consentVersion(bearer))
                .file("attachment", "one.png", PNG)
                .file("attachment", "two.png", PNG);

        HttpResponse<String> response = postMultipart(bearer, form);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(json(response).get("error").get("code").asText())
                .isEqualTo("feedback_attachment_unavailable");
        assertThat(count("SELECT count(*) FROM user_feedback")).isZero();
        // The successful first key and the failed second key are both cleaned up.
        verify(blobs, times(2)).deleteObject(anyString());
    }

    @Test
    void anEmptyAttachmentIsRefused() {
        String bearer = token();
        Multipart form = Multipart.create().withoutName()
                .field("content", "empty file")
                .field("consent_version", consentVersion(bearer))
                .file("attachment", "empty.png", new byte[0]);

        HttpResponse<String> response = postMultipart(bearer, form);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(json(response).get("error").get("code").asText())
                .isEqualTo("feedback_attachment_empty");
        assertThat(count("SELECT count(*) FROM user_feedback")).isZero();
        verify(blobs, never()).putObject(anyString(), any(Path.class), anyLong());
    }

    @Test
    void attachmentsOverTheTotalAreRefused() throws IOException {
        String bearer = token();
        Path dir = Files.createTempDirectory("feedback-total-");
        List<Path> files = new ArrayList<>();
        try {
            List<SubmitFeedbackUseCase.Upload> uploads = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                // Each file passes the per-file ceiling; together they cross the total.
                Path file = hugeMp4(dir, "clip" + i + ".mp4");
                files.add(file);
                uploads.add(new SubmitFeedbackUseCase.Upload(file, "clip" + i + ".mp4"));
            }

            assertThatThrownBy(() -> submit.submit("total", null, Map.of(), consentVersion(bearer),
                    false, uploads, AuthenticatedSubject.ofSession(DEMO_USER_ID)))
                    .isInstanceOfSatisfying(RequestException.class,
                            refused -> assertThat(refused.code())
                                    .isEqualTo(ErrorCode.FEEDBACK_ATTACHMENT_TOTAL_TOO_LARGE));
            assertThat(count("SELECT count(*) FROM user_feedback")).isZero();
            verify(blobs, never()).putObject(anyString(), any(Path.class), anyLong());
        } finally {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void anOversizedVideoIsRefused() throws IOException {
        String bearer = token();
        Path dir = Files.createTempDirectory("feedback-video-");
        Path file = mp4OfSize(dir, "big.mp4", FeedbackAttachmentSafety.MAX_VIDEO_BYTES + 1);
        try {
            assertThatThrownBy(() -> submit.submit("big video", null, Map.of(),
                    consentVersion(bearer), false,
                    List.of(new SubmitFeedbackUseCase.Upload(file, "big.mp4")),
                    AuthenticatedSubject.ofSession(DEMO_USER_ID)))
                    .isInstanceOfSatisfying(RequestException.class,
                            refused -> assertThat(refused.code())
                                    .isEqualTo(ErrorCode.FEEDBACK_ATTACHMENT_TOO_LARGE));
            assertThat(count("SELECT count(*) FROM user_feedback")).isZero();
            verify(blobs, never()).putObject(anyString(), any(Path.class), anyLong());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void anUnreadableAttachmentIsRefused() {
        String bearer = token();
        SubmitFeedbackUseCase.Upload missing = new SubmitFeedbackUseCase.Upload(
                Path.of("/nonexistent/skmr-v65/unreadable.png"), "unreadable.png");

        assertThatThrownBy(() -> submit.submit("unreadable", null, Map.of(), consentVersion(bearer),
                false, List.of(missing), AuthenticatedSubject.ofSession(DEMO_USER_ID)))
                .isInstanceOfSatisfying(RequestException.class, refused -> assertThat(refused.code())
                        .isEqualTo(ErrorCode.FEEDBACK_ATTACHMENT_UNREADABLE));
        assertThat(count("SELECT count(*) FROM user_feedback")).isZero();
        verify(blobs, never()).putObject(anyString(), any(Path.class), anyLong());
    }

    private static Path hugeMp4(Path dir, String name) throws IOException {
        return mp4OfSize(dir, name, 34L * 1024 * 1024);
    }

    private static Path mp4OfSize(Path dir, String name, long size) throws IOException {
        Path file = dir.resolve(name);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.write(mp4());
            raf.setLength(size);
        }
        return file;
    }

    @Test
    void aTextOnlySubmissionHasAnEmptyAttachmentList() {
        String bearer = token();
        HttpResponse<String> response = postJson(PATH, bearer, JSON.writeValueAsString(
                Map.of("content", "text only", "consent_version", consentVersion(bearer))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(json(response).get("attachments").size()).isZero();
        assertThat(count("""
                        SELECT count(*) FROM audit_event
                        WHERE action = 'feedback_submit' AND actor_user_id = :user
                          AND detail::jsonb->>'attachments' = '0'
                        """, Map.of("user", DEMO_USER_ID)))
                .as("the text-only path still records its audit row")
                .isEqualTo(1);
    }

    private HttpResponse<String> postMultipart(String bearer, Multipart multipart) {
        HttpRequest request = request(PATH, bearer)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build();
        return send(request);
    }

    private static byte[] mp4() {
        byte[] bytes = new byte[24];
        bytes[3] = 24;
        bytes[4] = 'f';
        bytes[5] = 't';
        bytes[6] = 'y';
        bytes[7] = 'p';
        bytes[8] = 'i';
        bytes[9] = 's';
        bytes[10] = 'o';
        bytes[11] = 'm';
        return bytes;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingBlobs {

        @Bean
        @Primary
        BlobStore recordingBlobStore() {
            BlobStore mock = Mockito.mock(BlobStore.class);
            Mockito.doNothing().when(mock).putObject(anyString(), any(Path.class), anyLong());
            Mockito.doNothing().when(mock).deleteObject(anyString());
            return mock;
        }
    }
}
