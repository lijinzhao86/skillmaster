package com.skillmasterai.modules.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.feedback.FeedbackAttachmentDraft;
import com.skillmasterai.modules.feedback.internal.FeedbackArchiveJob;
import com.skillmasterai.modules.feedback.internal.FeedbackRepository;
import com.skillmasterai.modules.feedback.internal.PgFeedbackService;
import com.skillmasterai.support.AbstractIT;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.ObjectMapper;

/**
 * The durability copy (v64): one gzip NDJSON object per row, the row stays, and a second sweep
 * does not upload the same document twice.
 */
@Sql("/sql/truncate-business-tables.sql")
class FeedbackArchiveJobIT extends AbstractIT {

    private static final String USER = "01M3HTG7GCCVBGRPAFFSVSF12W";

    @Autowired
    private FeedbackService feedback;

    @Autowired
    private FeedbackRepository repository;

    @Autowired
    private ObjectMapper json;

    @Test
    void writesOneCopyPerRowAndLeavesTheRowInPlace() throws Exception {
        Feedback row = feedback.create(USER, null, "archive me", "1.0", null,
                "2026-11-13T01:00:00Z");
        BlobStore blobs = Mockito.mock(BlobStore.class);
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            uploaded.set(Files.readAllBytes(invocation.getArgument(1)));
            return null;
        }).when(blobs).putObject(anyString(), any(Path.class), anyLong());
        Path directory = Files.createTempDirectory("feedback-archive-");
        try {
            FeedbackArchiveJob job = new FeedbackArchiveJob(feedback, blobs, json, directory);

            assertThat(job.archivePending(10)).isEqualTo(1);
            assertThat(uploaded.get()).isNotNull();
            try (GZIPInputStream gzip = new GZIPInputStream(
                    new ByteArrayInputStream(uploaded.get()))) {
                String text = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(text).contains(row.receipt()).contains("archive me")
                        .contains(row.id()).contains("\"source\":\"cli\"");
            }
            String key = jdbc.sql("SELECT archive_key FROM user_feedback WHERE id = :id")
                    .param("id", row.id())
                    .query(String.class)
                    .single();
            assertThat(key).startsWith("feedback/").endsWith(row.receipt() + ".ndjson.gz");
            assertThat(job.archivePending(10))
                    .as("the row now has a copy, so it is not pending")
                    .isZero();
        } finally {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void theArchiveCarriesAttachmentMetadataAndNoBytes() throws Exception {
        String attachmentId = "01JATTACHMENT0000000000000";
        String clipId = "01JATTACHMENT0000000000001";
        Feedback row = feedback.create(USER, null, "with attachments", "1.0", null,
                "2026-11-13T05:00:00Z", List.of(
                        new FeedbackAttachmentDraft(
                                attachmentId,
                                0,
                                "shot.png",
                                "image/png",
                                68,
                                "5e3d382db4dd83d59aa5742793ad6b7903409e865c83bcbc54835049f043bc15",
                                "feedback/attachments/2026/11/13/" + attachmentId + ".png"),
                        new FeedbackAttachmentDraft(
                                clipId,
                                1,
                                "clip.mp4",
                                "video/mp4",
                                24,
                                "a".repeat(64),
                                "feedback/attachments/2026/11/13/" + clipId + ".mp4")));
        BlobStore blobs = Mockito.mock(BlobStore.class);
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            uploaded.set(Files.readAllBytes(invocation.getArgument(1)));
            return null;
        }).when(blobs).putObject(anyString(), any(Path.class), anyLong());
        Path directory = Files.createTempDirectory("feedback-archive-attachment-");
        try {
            FeedbackArchiveJob job = new FeedbackArchiveJob(feedback, blobs, json, directory);

            assertThat(job.archivePending(10)).isEqualTo(1);
            try (GZIPInputStream gzip = new GZIPInputStream(
                    new ByteArrayInputStream(uploaded.get()))) {
                String text = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
                tools.jackson.databind.JsonNode document =
                        json.readValue(text.lines().findFirst().orElseThrow(),
                                tools.jackson.databind.JsonNode.class);
                tools.jackson.databind.JsonNode attachments = document.get("attachments");
                assertThat(attachments).hasSize(2);
                assertThat(attachments.get(0).get("filename").asText()).isEqualTo("shot.png");
                assertThat(attachments.get(0).get("media_type").asText()).isEqualTo("image/png");
                assertThat(attachments.get(0).get("size_bytes").asLong()).isEqualTo(68);
                assertThat(attachments.get(0).get("sha256").asText())
                        .isEqualTo("5e3d382db4dd83d59aa5742793ad6b7903409e865c83bcbc54835049f043bc15");
                assertThat(attachments.get(0).get("object_key").asText()).contains(attachmentId);
                assertThat(attachments.get(0).get("position").asInt()).isZero();
                assertThat(attachments.get(1).get("filename").asText()).isEqualTo("clip.mp4");
                assertThat(attachments.get(1).get("position").asInt()).isEqualTo(1);
                assertThat(text.length())
                        .as("metadata only — no attachment bytes")
                        .isLessThan(2048);
            }
            assertThat(job.archivePending(10)).as("a second sweep finds nothing").isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM feedback_attachment WHERE feedback_id = :id")
                    .param("id", row.id())
                    .query(Long.class)
                    .single())
                    .isEqualTo(2);
        } finally {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void markArchivedIsConditionalAndAMissingRowStaysMissing() {
        Feedback row = feedback.create(USER, null, "conditional", "1.0", null,
                "2026-11-13T03:00:00Z");

        assertThat(feedback.markArchived(row.id(), "feedback/x.ndjson.gz", "2026-11-13T03:01:00Z"))
                .isEqualTo(1);
        assertThat(feedback.markArchived(row.id(), "feedback/y.ndjson.gz", "2026-11-13T03:02:00Z"))
                .as("a second run loses the race and writes nothing")
                .isZero();
        assertThat(feedback.markArchived("01M3HTG7GCMISSING0000000", "k", "t")).isZero();
        assertThat(feedback.existsById(row.id())).isTrue();
        assertThat(feedback.existsById("01M3HTG7GCMISSING0000000")).isFalse();
    }

    @Test
    void withdrawingDeletesTheArchiveObjectBeforeTheRow() {
        Feedback row = feedback.create(USER, null, "withdraw me", "1.0", null,
                "2026-11-13T02:00:00Z");
        String key = "feedback/2026/11/13/" + row.receipt() + ".ndjson.gz";
        jdbc.sql("UPDATE user_feedback SET archive_key = :key WHERE id = :id")
                .param("key", key)
                .param("id", row.id())
                .update();
        BlobStore blobs = Mockito.mock(BlobStore.class);
        PgFeedbackService service = new PgFeedbackService(repository, blobs);

        assertThat(service.deleteByReceipt(row.receipt(), USER)).isTrue();
        Mockito.verify(blobs).deleteObject(key);
        assertThat(jdbc.sql("SELECT count(*) FROM user_feedback WHERE id = :id")
                .param("id", row.id())
                .query(Long.class)
                .single()).isZero();
    }
}
