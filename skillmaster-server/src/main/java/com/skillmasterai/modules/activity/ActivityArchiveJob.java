package com.skillmasterai.modules.activity;

import com.skillmasterai.modules.blob.BlobStore;
import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;

/**
 * Exports a week of events before its partition is dropped (v58, P4).
 *
 * <p><strong>The order is the whole design: write, count, upload, then drop.</strong> Dropping
 * first and exporting afterwards would make a failed export indistinguishable from a week that
 * never happened. A partition stays when any step fails, and the next run tries again.
 *
 * <p>The file is newline-delimited JSON, gzip-compressed: every row is a JSON object on its own
 * line. PostgreSQL renders it server-side through {@code COPY ... TO STDOUT}, so a week of events is
 * never materialised in this process. The format is what DuckDB's {@code read_json_auto} reads
 * without a schema, which is what makes the export checkable by hand.
 */
public final class ActivityArchiveJob {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityArchiveJob.class);

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final BlobStore blobs;
    private final ObjectMapper json;
    private final Path directory;

    public ActivityArchiveJob(JdbcClient jdbc, DataSource dataSource, BlobStore blobs,
            ObjectMapper json, Path directory) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.blobs = blobs;
        this.json = json;
        this.directory = directory;
    }

    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT24H")
    public void daily() {
        archiveExpired(LocalDate.now(ZoneOffset.UTC).minusDays(UsageHistory.WINDOW_DAYS));
    }

    /**
     * Archives every partition whose whole range is strictly older than {@code cutoff}.
     *
     * @return the partitions dropped in this run
     */
    public List<String> archiveExpired(LocalDate cutoff) {
        List<String> dropped = new java.util.ArrayList<>();
        for (String partition : partitions()) {
            LocalDate start = startOf(partition);
            if (start == null || start.plusWeeks(1).isAfter(cutoff)) {
                continue;
            }
            try {
                if (archive(partition)) {
                    dropped.add(partition);
                }
            } catch (org.springframework.dao.DataAccessException e) {
                // One partition's transient failure (a dropped connection, a lock timeout) must
                // not postpone every older partition behind it. It stays, and tomorrow retries.
                LOG.warn("could not archive {} this run; it stays", partition, e);
            }
        }
        return dropped;
    }

    private List<String> partitions() {
        return jdbc.sql("SELECT child.relname FROM pg_inherits"
                        + " JOIN pg_class child ON child.oid = inhrelid"
                        + " WHERE inhparent = 'activity_event'::regclass ORDER BY child.relname")
                .query(String.class)
                .list();
    }

    /** {@code activity_event_20261109} → 2026-11-09, or null for a name this job did not make. */
    private static LocalDate startOf(String partition) {
        String prefix = "activity_event_";
        if (!partition.startsWith(prefix)) {
            return null;
        }
        try {
            return LocalDate.parse(partition.substring(prefix.length()), SUFFIX);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean archive(String partition) {
        try {
            privateDirectory();
        } catch (java.io.IOException e) {
            LOG.warn("could not create the archive directory for {}", partition, e);
            return false;
        }

        // The lock is its own file so the staging file can stay a random `createTempFile` (0600):
        // the archive holds account ids, search words and file paths, and a predictable name under
        // the JVM temp directory would be readable by other local users and open to pre-placement.
        Path lockFile = directory.resolve(partition + ".lock");
        Path file = null;
        // Only an upload failure leaves the file: the export is complete at that point and the
        // object is the only thing missing, so an operator can retry with it. A failed export or a
        // row-count mismatch leaves a truncated file that must not be mistaken for an archive.
        boolean keepFile = false;
        try (java.nio.channels.FileChannel lockChannel = java.nio.channels.FileChannel.open(
                lockFile, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE)) {
            // Two instances can be pointed at one directory. The loser skips the partition this
            // run rather than truncating the winner's gzip mid-write; its next run finds the table
            // gone and treats that as done.
            java.nio.channels.FileLock lock;
            try {
                lock = lockChannel.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException e) {
                // The same JVM already holds this partition's lock (a manual run beside the
                // scheduled one); across processes tryLock returns null instead.
                lock = null;
            }
            if (lock == null) {
                LOG.info("another instance is archiving {}; skipping it this run", partition);
                return false;
            }
            try {
                Long rows = countRows(partition);
                if (rows == null) {
                    // The other instance won the same partition. Nothing left for this run to do.
                    return true;
                }
                // One staging file per partition: an upload-failed file from an earlier run is
                // replaced rather than accumulated.
                deleteStaleStagingFiles(partition);
                file = Files.createTempFile(directory, partition + "-", ".jsonl.gz");

                long copied = 0;
                try (Connection connection = dataSource.getConnection();
                        OutputStream out = new java.util.zip.GZIPOutputStream(
                                java.nio.channels.Channels.newOutputStream(
                                        java.nio.channels.FileChannel.open(file,
                                                java.nio.file.StandardOpenOption.WRITE)));
                        BufferedWriter writer = new BufferedWriter(
                                new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
                    // Server-side cursor: without autoCommit off and a fetch size, the driver pulls
                    // the whole week into this process before the first row is written.
                    connection.setAutoCommit(false);
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT * FROM " + quote(partition),
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                        statement.setFetchSize(500);
                        try (ResultSet result = statement.executeQuery()) {
                            ResultSetMetaData columns = result.getMetaData();
                            while (result.next()) {
                                writer.write(json.writeValueAsString(row(result, columns)));
                                writer.newLine();
                                copied++;
                            }
                        }
                    }
                    connection.commit();
                } catch (Exception e) {
                    LOG.warn("could not export partition {}", partition, e);
                    return false;
                }
                if (copied != rows) {
                    LOG.error("partition {} held {} rows but {} were exported; it stays",
                            partition, rows, copied);
                    return false;
                }

                try {
                    blobs.putObject("activity/" + partition + ".jsonl.gz", file, Files.size(file));
                } catch (Exception e) {
                    keepFile = true;
                    LOG.warn("could not upload the archive of {}; it stays at {}", partition, file,
                            e);
                    return false;
                }

                try {
                    jdbc.sql("DROP TABLE " + quote(partition)).update();
                } catch (org.springframework.dao.DataAccessException e) {
                    if (!missingTable(e)) {
                        throw e;
                    }
                }
                LOG.info("archived {} rows from {} and dropped it", rows, partition);
                return true;
            } finally {
                lock.release();
            }
        } catch (java.io.IOException e) {
            LOG.warn("could not open the archive lock for {}", partition, e);
            return false;
        } finally {
            if (file != null && !keepFile) {
                // Success, or a failed export: either way this staging file has no future use.
                try {
                    Files.deleteIfExists(file);
                } catch (java.io.IOException e) {
                    LOG.warn("left the archive file for {}", partition, e);
                }
            }
        }
    }

    /**
     * 0700 when the platform has POSIX permissions; the archive holds personal behaviour.
     *
     * <p>A directory that already exists is tightened, not trusted: an older build (or another
     * local user) may have made it 0755, and the default name under the JVM temp directory is
     * predictable. A symbolic link is refused for the same reason — otherwise a planted link
     * could redirect the export or let its owner swap the file between write and upload.
     */
    private void privateDirectory() throws java.io.IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new java.io.IOException("refusing a symbolic link as the archive directory: "
                    + directory);
        }
        try {
            Files.createDirectories(directory, java.nio.file.attribute.PosixFilePermissions
                    .asFileAttribute(java.nio.file.attribute.PosixFilePermissions
                            .fromString("rwx------")));
            Files.setPosixFilePermissions(directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            assertOwned(directory);
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(directory);
        }
    }

    /**
     * Refuses a directory owned by another user. chmod alone is not enough: a process running as
     * root (or with CAP_FOWNER) can chmod somebody else's directory, and its owner could then swap
     * the staging file between the export and the upload.
     *
     * <p>The probe file's owner is by definition this process's effective UID, so comparing the
     * two `unix:uid` attributes needs no access to the process UID and never compares user names
     * (a UID without a passwd entry has no usable name).
     */
    private static void assertOwned(Path dir) throws java.io.IOException {
        Path probe = Files.createTempFile(dir, ".owner-", ".probe");
        try {
            long ours = ((Number) Files.getAttribute(probe, "unix:uid")).longValue();
            long owner = ((Number) Files.getAttribute(dir, "unix:uid",
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)).longValue();
            if (ours != owner) {
                throw new java.io.IOException("archive directory is owned by uid " + owner
                        + ", not " + ours + ": " + dir);
            }
        } catch (UnsupportedOperationException e) {
            // Not a Unix filesystem: the symbolic-link check is all this platform offers.
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private void deleteStaleStagingFiles(String partition) {
        String prefix = partition + "-";
        try (var files = Files.list(directory)) {
            for (Path old : files.toList()) {
                String name = old.getFileName().toString();
                if (name.startsWith(prefix) && name.endsWith(".jsonl.gz")) {
                    Files.deleteIfExists(old);
                }
            }
        } catch (java.io.IOException e) {
            LOG.warn("could not clear old staging files for {}", partition, e);
        }
    }

    /** @return the row count, or null when another instance already dropped the partition */
    private Long countRows(String partition) {
        try {
            return jdbc.sql("SELECT count(*) FROM " + quote(partition)).query(Long.class).single();
        } catch (org.springframework.dao.DataAccessException e) {
            if (missingTable(e)) {
                return null;
            }
            throw e;
        }
    }

    private static boolean missingTable(org.springframework.dao.DataAccessException failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sql
                    && "42P01".equals(sql.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private Map<String, Object> row(ResultSet result, ResultSetMetaData columns)
            throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 1; index <= columns.getColumnCount(); index++) {
            String label = columns.getColumnLabel(index);
            Object value = result.getObject(index);
            if ("detail".equals(label) && value instanceof String text) {
                // A nested object, not an escaped string: the archive is read by `read_json_auto`
                // (TD §数据模型), and a JSON value stored as a string would need a second parse.
                try {
                    value = json.readTree(text);
                } catch (RuntimeException ignored) {
                    // A shape this build does not know is still exported as it was written.
                }
            }
            row.put(label, value);
        }
        return row;
    }

    /** The name came from {@code pg_class} and is also checked to start with the prefix. */
    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
