package com.skillmasterai.modules.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The reader against a fake GitHub: one commits call, one codeload download, same commit. */
class GitHubContentReaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicInteger codeloadCalls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/acme/tools/commits", exchange ->
                respond(exchange, 200, "[{\"sha\":\"abc123\"}]"));
        server.createContext("/repos/acme/tools/contents/skills", exchange ->
                respond(exchange, 200, """
                        [{"type":"dir","name":"b"},{"type":"file","name":"SKILL.md"},
                         {"type":"dir","name":"a"},{"type":"dir","name":".hidden"}]
                        """));
        server.createContext("/repos/acme/missing/commits", exchange ->
                respond(exchange, 404, "{}"));
        server.createContext("/repos/acme/limited/commits", exchange ->
                respond(exchange, 429, "{}"));
        server.createContext("/repos/acme/locked/commits", exchange ->
                respond(exchange, 200, "[{\"sha\":\"forbidden\"}]"));
        server.createContext("/codeload/acme/locked/tar.gz/forbidden", exchange -> {
            codeloadCalls.incrementAndGet();
            respond(exchange, 403, "{}");
        });
        server.createContext("/codeload/acme/tools/tar.gz/abc123", exchange -> {
            codeloadCalls.incrementAndGet();
            byte[] body = archive();
            exchange.getResponseHeaders().add("Content-Type", "application/gzip");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void oneDownloadReadsTheDirectoryAndItsNearestLicense() {
        GitHubContentReader reader = reader();

        GitHubContentReader.Fetched fetched = fetch(reader, "acme/tools", "skills/ab", "");

        assertThat(fetched.error()).isNull();
        assertThat(fetched.files()).extracting(GitHubContentReader.File::relpath)
                .containsExactlyInAnyOrder("skills/ab/SKILL.md", "skills/ab/run.py");
        assertThat(fetched.license()).isNotNull();
        assertThat(fetched.license().spdx()).isEqualTo("MIT");
        assertThat(fetched.license().path()).isEqualTo("LICENSE");
        assertThat(codeloadCalls).hasValue(1);
    }

    @Test
    void revisionReadsTheLatestCommitForThePath() {
        assertThat(reader().revision("acme/tools", "skills/ab").sha()).isEqualTo("abc123");
        assertThat(reader().revision("acme/missing", "skills").error()).isEqualTo("not_found");
    }

    @Test
    void childrenListsVisibleDirectoriesOnly() {
        GitHubContentReader.Children children = reader().children("acme/tools", "skills");

        assertThat(children.error()).isNull();
        assertThat(children.names()).containsExactly("a", "b");
    }

    @Test
    void aRateLimitIsReportedAsSuch() {
        assertThat(reader().revision("acme/limited", "skills/ab").error())
                .isEqualTo("rate_limited");
    }

    @Test
    void anErrorResponseDoesNotHideTheStatusBehindANetworkFailure() {
        assertThat(reader().download("acme/locked").error()).isEqualTo("rate_limited");
        assertThat(codeloadCalls).hasValue(1);
    }

    @Test
    void aRepositoryThatCannotBeDownloadedIsTriedOnceAndFailsEverySkillInIt() {
        var source = new com.skillmasterai.usecase.sync.GitHubContentSource(reader(), JSON);
        var first = new com.skillmasterai.usecase.sync.SyncEntry("github", "acme/locked",
                "skills/a", com.skillmasterai.usecase.sync.SyncEntry.Mode.ONE, List.of(), "");
        var second = new com.skillmasterai.usecase.sync.SyncEntry("github", "acme/locked",
                "skills/b", com.skillmasterai.usecase.sync.SyncEntry.Mode.ONE, List.of(), "");

        source.beginRun();
        try {
            var a = (com.skillmasterai.usecase.sync.FetchFailed) source.fetch(first, "abc123");
            var b = (com.skillmasterai.usecase.sync.FetchFailed) source.fetch(second, "abc123");

            assertThat(a.reason()).isEqualTo("rate_limited");
            assertThat(b.reason()).isEqualTo("rate_limited");
            assertThat(codeloadCalls).hasValue(1);
        } finally {
            source.endRun();
        }
    }

    @Test
    void aConfiguredLicensePathWinsOverTheNearestOne() {
        GitHubContentReader.Fetched fetched =
                fetch(reader(), "acme/tools", "skills/ab", "licenses/APACHE.txt");

        assertThat(fetched.error()).isNull();
        assertThat(fetched.license().spdx()).isEqualTo("Apache-2.0");
        assertThat(fetched.license().path()).isEqualTo("licenses/APACHE.txt");
    }

    @Test
    void aConfiguredLicensePathMissingFromTheCommitFailsTheEntry() {
        assertThat(fetch(reader(), "acme/tools", "skills/ab", "licenses/NOPE").error())
                .isEqualTo("license_path_missing");
    }

    @Test
    void theSourceDownloadsOneArchiveForEverySkillInTheRepository() {
        var source = new com.skillmasterai.usecase.sync.GitHubContentSource(reader(), JSON);
        var first = new com.skillmasterai.usecase.sync.SyncEntry("github", "acme/tools",
                "skills/ab", com.skillmasterai.usecase.sync.SyncEntry.Mode.ONE, List.of(), "");
        var second = new com.skillmasterai.usecase.sync.SyncEntry("github", "acme/tools",
                "skills/other", com.skillmasterai.usecase.sync.SyncEntry.Mode.ONE, List.of(), "");

        source.beginRun();
        try {
            source.fetch(first, "abc123");
            var fetched = (com.skillmasterai.usecase.sync.Fetched) source.fetch(second, "abc123");

            assertThat(fetched.files()).extracting(
                            com.skillmasterai.usecase.sync.FetchedFile::relpath)
                    .containsExactly("skills/other/SKILL.md");
            assertThat(codeloadCalls).hasValue(1);
        } finally {
            source.endRun();
        }
    }

    @Test
    void theAdapterCarriesTheConfiguredLicensePathToTheReader() {
        var source = new com.skillmasterai.usecase.sync.GitHubContentSource(reader(), JSON);
        var entry = new com.skillmasterai.usecase.sync.SyncEntry("github", "acme/tools",
                "skills/ab", com.skillmasterai.usecase.sync.SyncEntry.Mode.ONE, List.of(),
                "licenses/APACHE.txt");

        source.beginRun();
        try {
            var fetched = (com.skillmasterai.usecase.sync.Fetched) source.fetch(entry, "abc123");

            assertThat(fetched.license().spdx()).isEqualTo("Apache-2.0");
            assertThat(fetched.license().path()).isEqualTo("licenses/APACHE.txt");
        } finally {
            source.endRun();
        }
    }

    private static GitHubContentReader.Fetched fetch(GitHubContentReader reader, String locator,
            String path, String licensePath) {
        GitHubContentReader.Downloaded downloaded = reader.download(locator);
        if (downloaded.error() != null) {
            return GitHubContentReader.Fetched.failed(downloaded.error());
        }
        try {
            return reader.filesIn(downloaded.file(), locator, path, licensePath, downloaded.ref());
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(downloaded.file());
            } catch (IOException ignored) {
                // The test's temporary file; nothing else depends on it being gone.
            }
        }
    }

    @Test
    void aLocatorThatIsNotOwnerSlashRepoIsRefusedBeforeAnyRequest() {
        assertThat(reader().revision("not a repo", "").error()).isEqualTo("invalid_locator");
        assertThat(codeloadCalls).hasValue(0);
    }

    private GitHubContentReader reader() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GitHubContentReader(JSON, base, base + "/codeload", "test-token",
                new GitHubContentReader.Limits(50, 1024, 4096, 1_000_000, 100_000));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static byte[] archive() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
                    TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
                file(tar, "tools-abc123/skills/ab/SKILL.md",
                        "---\nname: ab\ndescription: d\n---\n");
                file(tar, "tools-abc123/skills/ab/run.py", "print(1)");
                file(tar, "tools-abc123/skills/other/SKILL.md", "# other");
                file(tar, "tools-abc123/LICENSE", "MIT License\n\nPermission is hereby granted,"
                        + " free of charge, to any person obtaining a copy");
                file(tar, "tools-abc123/licenses/APACHE.txt",
                        "Apache License\nVersion 2.0, January 2004");
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void file(TarArchiveOutputStream tar, String name, String content)
            throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }
}
