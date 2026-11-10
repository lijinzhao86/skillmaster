package com.skillmasterai.modules.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.support.AbstractIT;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.ObjectMapper;

/**
 * The archive's only non-negotiable order (v58 A15): a partition is dropped after its rows were
 * written, counted, and handed to object storage — never before. The file is NDJSON in gzip,
 * readable by DuckDB's {@code read_json_auto}.
 */
@Sql("/sql/truncate-business-tables.sql")
class ActivityArchiveJobIT extends AbstractIT {

    private static final String ACTOR = "01M3HTG7GCCVBGRPAFFSVSF12W";

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private ObjectMapper json;

    private Path directory;

    private final java.util.List<Path> extraDirectories = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() throws IOException {
        java.util.List<Path> all = new java.util.ArrayList<>(extraDirectories);
        if (directory != null) {
            all.add(directory);
        }
        for (Path dir : all) {
            if (!Files.exists(dir)) {
                continue;
            }
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(dir);
        }
        // The partition this class recreates is entirely its own; leaving it behind would make the
        // next run's archive sweep pick up a test fixture.
        jdbc.sql("DROP TABLE IF EXISTS " + oldPartitionName()).update();
    }

    @Test
    void exportsCountsUploadsThenDrops() throws IOException {
        String partition = oldPartition();
        BlobStore blobs = mock(BlobStore.class);
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            uploaded.set(Files.readAllBytes(invocation.getArgument(1)));
            return null;
        }).when(blobs).putObject(eq("activity/" + partition + ".jsonl.gz"), any(), anyLong());

        List<String> dropped = job(blobs).archiveExpired(LocalDate.now(ZoneOffset.UTC)
                .minusDays(UsageHistory.WINDOW_DAYS));

        assertThat(dropped).contains(partition);
        assertThat(partitionExists(partition)).isFalse();
        assertThat(uploaded.get()).isNotNull();
        List<String> lines = lines(uploaded.get());
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("\"actor_user_id\":\"" + ACTOR + "\"");
        // `detail` is written as an object, not as an escaped string: `read_json_auto` opens it
        // without a second parse (TD §数据模型).
        assertThat(lines.get(0)).contains("\"detail\":{\"results\":[");
        // The staging copy is only a path to OSS; once the object is up it must not accumulate.
        assertThat(stagingFiles()).isEmpty();
    }

    @Test
    void aFailedUploadKeepsThePartition() throws IOException {
        String partition = oldPartition();
        BlobStore blobs = mock(BlobStore.class);
        doThrow(new IllegalStateException("object storage is off"))
                .when(blobs).putObject(eq("activity/" + partition + ".jsonl.gz"), any(), anyLong());

        ActivityArchiveJob job = job(blobs);
        List<String> dropped = job.archiveExpired(LocalDate.now(ZoneOffset.UTC)
                .minusDays(UsageHistory.WINDOW_DAYS));

        assertThat(dropped).doesNotContain(partition);
        assertThat(partitionExists(partition)).isTrue();
        verify(blobs).putObject(eq("activity/" + partition + ".jsonl.gz"), any(), anyLong());
        // ArchiveSettings promises an operator a file to retry; the failed run keeps it.
        assertThat(stagingFiles()).hasSize(1);
        Path kept = stagingFiles().get(0);
        assertThat(Files.getPosixFilePermissions(kept))
                .as("the archive holds personal behaviour; only its owner may read it")
                .containsExactly(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);

        // A second failed run replaces that file instead of adding another one.
        job.archiveExpired(LocalDate.now(ZoneOffset.UTC).minusDays(UsageHistory.WINDOW_DAYS));
        assertThat(stagingFiles()).hasSize(1);
    }

    @Test
    void aFailedExportLeavesNoTruncatedFileBehind() throws IOException {
        String partition = oldPartition();
        BlobStore blobs = mock(BlobStore.class);
        javax.sql.DataSource broken = mock(javax.sql.DataSource.class);
        try {
            org.mockito.Mockito.when(broken.getConnection())
                    .thenThrow(new java.sql.SQLException("database is down"));
        } catch (java.sql.SQLException e) {
            throw new AssertionError(e);
        }

        List<String> dropped = job(blobs, broken).archiveExpired(
                LocalDate.now(ZoneOffset.UTC).minusDays(UsageHistory.WINDOW_DAYS));

        assertThat(dropped).doesNotContain(partition);
        assertThat(partitionExists(partition)).isTrue();
        // A half-written gzip must never be left where an operator could mistake it for a backup.
        assertThat(stagingFiles()).isEmpty();
    }

    @Test
    void aRowCountMismatchKeepsThePartitionAndDropsTheFile() throws IOException {
        String partition = oldPartition();
        BlobStore blobs = mock(BlobStore.class);
        org.springframework.jdbc.core.simple.JdbcClient fake =
                mock(org.springframework.jdbc.core.simple.JdbcClient.class);
        var partitionsSpec = mock(
                org.springframework.jdbc.core.simple.JdbcClient.StatementSpec.class);
        var names = mock(org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec.class);
        org.mockito.Mockito.when(fake.sql(org.mockito.ArgumentMatchers.contains("pg_inherits")))
                .thenReturn(partitionsSpec);
        org.mockito.Mockito.when(partitionsSpec.query(String.class)).thenReturn(names);
        org.mockito.Mockito.when(names.list()).thenReturn(List.of(partition));
        var countSpec = mock(
                org.springframework.jdbc.core.simple.JdbcClient.StatementSpec.class);
        var counts = mock(org.springframework.jdbc.core.simple.JdbcClient.MappedQuerySpec.class);
        org.mockito.Mockito.when(fake.sql(org.mockito.ArgumentMatchers.startsWith(
                "SELECT count(*) FROM \"" + partition))).thenReturn(countSpec);
        org.mockito.Mockito.when(countSpec.query(Long.class)).thenReturn(counts);
        // Four rows counted, three exported: the same check that keeps a half-written week.
        org.mockito.Mockito.when(counts.single()).thenReturn(4L);

        List<String> dropped = job(blobs, dataSource, fake).archiveExpired(
                LocalDate.now(ZoneOffset.UTC).minusDays(UsageHistory.WINDOW_DAYS));

        assertThat(dropped).doesNotContain(partition);
        assertThat(partitionExists(partition)).isTrue();
        org.mockito.Mockito.verifyNoInteractions(blobs);
        assertThat(stagingFiles()).isEmpty();
    }

    @Test
    void anExistingDirectoryIsTightenedAndASymbolicLinkIsRefused() throws IOException {
        String partition = oldPartition();
        BlobStore blobs = mock(BlobStore.class);

        Path loose = Files.createTempDirectory("activity-archive-loose-");
        extraDirectories.add(loose);
        Files.setPosixFilePermissions(loose, java.nio.file.attribute.PosixFilePermissions
                .fromString("rwxr-xr-x"));
        ActivityArchiveJob tighten = new ActivityArchiveJob(jdbc, dataSource, blobs, json, loose);
        assertThat(tighten.archiveExpired(LocalDate.now(ZoneOffset.UTC)
                .minusDays(UsageHistory.WINDOW_DAYS))).contains(partition);
        assertThat(Files.getPosixFilePermissions(loose))
                .as("an old 0755 directory is tightened to 0700")
                .containsExactly(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);

        // A link would let its owner swap the file between export and upload.
        String second = oldPartition();
        Path target = Files.createTempDirectory("activity-archive-target-");
        Path holder = Files.createTempDirectory("activity-archive-link-");
        extraDirectories.add(target);
        extraDirectories.add(holder);
        Path link = holder.resolve("archive");
        Files.createSymbolicLink(link, target);
        ActivityArchiveJob linked = new ActivityArchiveJob(jdbc, dataSource, blobs, json, link);
        assertThat(linked.archiveExpired(LocalDate.now(ZoneOffset.UTC)
                .minusDays(UsageHistory.WINDOW_DAYS))).doesNotContain(second);
        assertThat(target.toFile().list()).as("nothing is written through the link").isEmpty();
    }

    @Test
    void aPartitionAnotherInstanceIsArchivingIsSkipped() throws IOException {
        String partition = oldPartition();
        BlobStore blobs = mock(BlobStore.class);
        ActivityArchiveJob job = job(blobs);
        Path file = directory.resolve(partition + ".lock");

        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                java.nio.channels.FileLock lock = channel.lock()) {
            List<String> dropped = job.archiveExpired(LocalDate.now(ZoneOffset.UTC)
                    .minusDays(UsageHistory.WINDOW_DAYS));

            assertThat(dropped).doesNotContain(partition);
            assertThat(partitionExists(partition)).isTrue();
        }
    }

    private ActivityArchiveJob job(BlobStore blobs) throws IOException {
        return job(blobs, dataSource);
    }

    private ActivityArchiveJob job(BlobStore blobs, javax.sql.DataSource source) throws IOException {
        return job(blobs, source, Files.createTempDirectory("activity-archive-"));
    }

    private ActivityArchiveJob job(BlobStore blobs, javax.sql.DataSource source,
            org.springframework.jdbc.core.simple.JdbcClient client) throws IOException {
        directory = Files.createTempDirectory("activity-archive-");
        return new ActivityArchiveJob(client, source, blobs, json, directory);
    }

    private ActivityArchiveJob job(BlobStore blobs, javax.sql.DataSource source,
            Path where) throws IOException {
        directory = where;
        return new ActivityArchiveJob(jdbc, source, blobs, json, directory);
    }


    private String oldPartitionName() {
        LocalDate start = LocalDate.now(ZoneOffset.UTC).minusDays(120);
        return "activity_event_" + DateTimeFormatter.BASIC_ISO_DATE.format(start);
    }

    private String oldPartition() {
        LocalDate start = LocalDate.now(ZoneOffset.UTC).minusDays(120);
        String name = oldPartitionName();
        String end = start.plusWeeks(1) + "T00:00:00Z";
        jdbc.sql("DROP TABLE IF EXISTS " + name).update();
        jdbc.sql("CREATE TABLE " + name + " PARTITION OF activity_event"
                        + " FOR VALUES FROM ('" + start + "T00:00:00Z') TO ('" + end + "')")
                .update();
        for (int i = 0; i < 3; i++) {
            jdbc.sql("INSERT INTO activity_event (actor_user_id, kind, detail, at)"
                            + " VALUES (:actor, 'search', :detail, :at)")
                    .param("actor", ACTOR)
                    .param("detail", i == 0
                            ? "{\"results\":[{\"skill_id\":\"demo/a\",\"address\":\"demo/a\","
                                    + "\"rank\":1}],\"skill_ids\":[\"demo/a\"]}"
                            : "{}")
                    .param("at", start + "T0" + i + ":00:00Z")
                    .update();
        }
        return name;
    }

    /** The `.jsonl.gz` files, not the per-partition `.lock` files. */
    private List<Path> stagingFiles() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".jsonl.gz"))
                    .toList();
        }
    }

    private boolean partitionExists(String name) {
        return jdbc.sql("SELECT count(*) FROM pg_class WHERE relname = :name")
                .param("name", name)
                .query(Long.class).single() > 0;
    }

    private static List<String> lines(byte[] gzip) throws IOException {
        List<String> lines = new ArrayList<>();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(gzip)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }
}
