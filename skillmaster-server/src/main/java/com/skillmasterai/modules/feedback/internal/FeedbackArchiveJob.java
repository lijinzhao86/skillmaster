package com.skillmasterai.modules.feedback.internal;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.feedback.Feedback;
import com.skillmasterai.modules.feedback.FeedbackService;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes one gzip NDJSON object per feedback row that has no durability copy yet (v64).
 *
 * <p><strong>The row stays.</strong> Unlike the activity export, this is a copy, not a move: the
 * privacy policy says the content is kept, and the database is where the product reads it. The
 * object is what survives a lost volume, not what replaces the table.
 *
 * <p><strong>One object per row.</strong> A withdrawal names exactly one receipt, so deleting the
 * object is a single key delete; packing a day into one file would force a rewrite on every
 * withdrawal. The volume is one document per submission, not one per request.
 */
public final class FeedbackArchiveJob {

    private static final Logger LOG = LoggerFactory.getLogger(FeedbackArchiveJob.class);

    private static final int BATCH = 500;

    /** A run keeps taking batches until the table is drained or this budget is spent. */
    private static final int MAX_BATCHES = 40;

    /** Staging files left by a killed process hold feedback content; they are not kept. */
    private static final Duration STALE_STAGING = Duration.ofHours(1);

    /** A run is bounded in wall-clock time as well as batches: a half-dead OSS must not hold the
     * shared scheduler for hours. */
    private static final Duration RUN_BUDGET = Duration.ofMinutes(20);

    private final FeedbackService feedback;
    private final BlobStore blobs;
    private final ObjectMapper json;
    private final Path directory;

    public FeedbackArchiveJob(FeedbackService feedback, BlobStore blobs, ObjectMapper json,
            Path directory) {
        this.feedback = feedback;
        this.blobs = blobs;
        this.json = json;
        this.directory = directory;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT24H")
    public void daily() {
        // One day between runs means a backlog must not take a day per batch: keep taking batches
        // while rows are pending and progress is being made. A batch can be short because a row
        // failed, so "shorter than BATCH" is not the stopping rule; zero written is.
        Instant deadline = Instant.now().plus(RUN_BUDGET);
        for (int round = 0; round < MAX_BATCHES; round++) {
            if (Instant.now().isAfter(deadline)) {
                LOG.warn("feedback archive stopped at its time budget; the rest waits for the next run");
                return;
            }
            int written = archivePending(BATCH);
            if (written == 0) {
                return;
            }
        }
        LOG.warn("feedback archive stopped after {} batches; the rest waits for the next run",
                MAX_BATCHES);
    }

    /** @return how many rows got a copy; a failed row is left for the next run */
    public int archivePending(int limit) {
        List<Feedback> rows = feedback.pendingArchive(limit);
        if (rows.isEmpty()) {
            return 0;
        }
        int written = 0;
        try {
            privateDirectory();
            deleteStaleStaging();
        } catch (IOException e) {
            LOG.warn("feedback archive directory {} is not usable", directory, e);
            return 0;
        }
        for (Feedback row : rows) {
            try {
                write(row);
                written++;
            } catch (IllegalStateException noStorage) {
                // Object storage is off (local run, default test profile): nothing was written and
                // every row stays pending. One line, not one per row.
                LOG.info("feedback archive skipped: {}", noStorage.getMessage());
                break;
            } catch (RuntimeException e) {
                LOG.warn("feedback archive failed for {}", row.receipt(), e);
            }
        }
        return written;
    }

    private void write(Feedback row) {
        Path file = null;
        try {
            file = Files.createTempFile(directory, "feedback-", ".ndjson.gz");
            try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(file));
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(gzip, StandardCharsets.UTF_8))) {
                writer.write(json.writeValueAsString(document(row)));
                writer.newLine();
            }
            String key = PgFeedbackService.archiveKey(row);
            blobs.putObject(key, file, Files.size(file));
            recordArchive(row, key);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the feedback archive file", e);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // The staging copy is already uploaded or abandoned; the row stays pending.
                }
            }
        }
    }

    /**
     * Records where the copy lives, or removes it when the row that named it is gone.
     *
     * <p><strong>The row still existing means the object stays.</strong> A zero-row update can also
     * be a lost race to another run, which recorded the same deterministic key; deleting the object
     * then would remove that run's copy.
     */
    void recordArchive(Feedback row, String key) {
        if (feedback.markArchived(row.id(), key, Timestamps.now()) != 0
                || feedback.existsById(row.id())) {
            return;
        }
        try {
            blobs.deleteObject(key);
        } catch (RuntimeException e) {
            // Nobody will retry this key: the row is gone. The error names the object so an
            // operator can remove it by hand.
            LOG.error("withdrawn feedback object {} could not be deleted; remove it manually",
                    key, e);
        }
    }

    /**
     * Refuses a symbolic link and makes the directory owner-only, the same rule the activity
     * archive applies: the staging file holds the feedback content before it is uploaded.
     */
    private void privateDirectory() throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("refusing a symbolic link as the archive directory: " + directory);
        }
        try {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(directory);
        }
    }

    private void deleteStaleStaging() {
        Instant cutoff = Instant.now().minus(STALE_STAGING);
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().startsWith("feedback-")
                            && path.getFileName().toString().endsWith(".ndjson.gz"))
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOG.warn("could not remove stale feedback staging file {}", path, e);
                        }
                    });
        } catch (IOException e) {
            LOG.warn("could not scan the feedback archive directory {}", directory, e);
        }
    }

    private Map<String, Object> document(Feedback row) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", row.id());
        document.put("receipt", row.receipt());
        document.put("user_id", row.userId());
        document.put("skill_address", row.skillAddress());
        document.put("content", row.content());
        document.put("content_sha256", row.contentSha256());
        document.put("consent_version", row.consentVersion());
        document.put("consent_at", row.consentAt());
        document.put("source", row.source());
        document.put("diagnostics", row.diagnostics());
        document.put("created_at", row.createdAt());
        return document;
    }
}
