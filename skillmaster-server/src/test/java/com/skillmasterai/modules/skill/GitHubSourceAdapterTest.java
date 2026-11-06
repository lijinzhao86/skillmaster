package com.skillmasterai.modules.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The GitHub adapter against a local server: the fixed endpoints, the generic signal array, and the
 * failure that leaves the page's identity intact (v49 T3).
 *
 * <p>A local HTTP server rather than a mocked client, because the behaviour under test is the mapping
 * of an actual answer — status codes, a base64 license body, a commit array — into signals. A mock
 * would assert the same shape twice.</p>
 */
class GitHubSourceAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private GitHubSourceAdapter adapter;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/", this::handle);
        server.start();
        adapter = new GitHubSourceAdapter(JSON,
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsRepositoryFactsIntoTheGenericSignalArray() throws Exception {
        SourceAdapter.SourceBinding binding =
                adapter.parse("coreyhaines31/marketingskills", "skills/ab-testing");

        SourceAdapter.FetchedSource fetched = adapter.fetch(binding, null);

        assertThat(fetched.status()).isEqualTo(SourceSnapshot.OK);
        assertThat(fetched.revision()).isEqualTo("8f3c2a1d4e5f60718293a4b5c6d7e8f901234567");
        JsonNode signals = JSON.readTree(fetched.signalsJson());
        assertThat(signals).hasSize(3);
        assertThat(signals.get(0).path("key").asText()).isEqualTo("stars");
        assertThat(signals.get(0).path("display").asText()).isEqualTo("1.2k");
        assertThat(signals.get(0).path("url").asText())
                .isEqualTo("https://github.com/coreyhaines31/marketingskills/stargazers");
        assertThat(signals.get(1).path("key").asText()).isEqualTo("forks");
        assertThat(signals.get(2).path("key").asText()).isEqualTo("last_commit");
        assertThat(signals.get(2).path("value").asText()).isEqualTo("2026-10-30");

        assertThat(fetched.license()).isNotNull();
        assertThat(fetched.license().spdx()).isEqualTo("MIT");
        assertThat(fetched.license().sourcePath()).isEqualTo("LICENSE");
        assertThat(fetched.license().copyright()).contains("Copyright (c) 2025 Corey Haines");
        assertThat(fetched.license().text()).contains("Permission is hereby granted");
    }

    @Test
    void theLicenseInTheSkillDirectoryWinsOverTheRepositoryRoot() {
        SourceAdapter.SourceBinding binding = adapter.parse("acme/skills", "skills/ab-testing");

        SourceAdapter.FetchedSource fetched = adapter.fetch(binding, null);

        // The repository root says MIT; the skill's own directory says Apache-2.0. The nearest one
        // is the license of the version, which is the whole point of walking from the skill.
        assertThat(fetched.license()).isNotNull();
        assertThat(fetched.license().spdx()).isEqualTo("Apache-2.0");
        assertThat(fetched.license().sourcePath()).isEqualTo("skills/ab-testing/LICENSE");
        assertThat(fetched.license().text()).contains("Apache License");
    }

    @Test
    void aLicenseInAParentDirectoryIsFoundByWalkingUp() {
        SourceAdapter.SourceBinding binding = adapter.parse("beta/skills", "skills/ab-testing");

        SourceAdapter.FetchedSource fetched = adapter.fetch(binding, null);

        assertThat(fetched.license()).isNotNull();
        assertThat(fetched.license().spdx()).isEqualTo("Apache-2.0");
        assertThat(fetched.license().sourcePath()).isEqualTo("skills/LICENSE");
    }

    @Test
    void aMissingRepositoryIsAnErrorSnapshotNotAThrow() {
        SourceAdapter.SourceBinding binding = adapter.parse("missing/repo", "");

        SourceAdapter.FetchedSource fetched = adapter.fetch(binding, null);

        assertThat(fetched.status()).isEqualTo(SourceSnapshot.ERROR);
        assertThat(fetched.signalsJson()).isEqualTo("[]");
        assertThat(fetched.revision()).isNull();
        assertThat(fetched.license()).isNull();
    }

    @Test
    void parsesOwnerRepoAndRejectsAnythingElse() {
        SourceAdapter.SourceBinding binding = adapter.parse("coreyhaines31/marketingskills",
                "skills/ab-testing");
        assertThat(binding.kind()).isEqualTo("github");
        assertThat(binding.label()).isEqualTo("GitHub");
        assertThat(binding.url()).isEqualTo("https://github.com/coreyhaines31/marketingskills");

        assertThatThrownBy(() -> adapter.parse("https://github.com/a/b", ""))
                .isInstanceOf(InvalidSourceException.class);
        assertThatThrownBy(() -> adapter.parse("no-slash", ""))
                .isInstanceOf(InvalidSourceException.class);
        assertThatThrownBy(() -> adapter.parse("owner/repo", "../outside"))
                .isInstanceOf(InvalidSourceException.class)
                .hasMessageContaining("..");
        assertThatThrownBy(() -> adapter.parse("owner/repo", "/absolute"))
                .isInstanceOf(InvalidSourceException.class);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/repos/missing/repo")) {
            respond(exchange, 404, "{}");
            return;
        }
        if (path.equals("/repos/coreyhaines31/marketingskills")) {
            respond(exchange, 200, """
                    {"stargazers_count":1234,"forks_count":56,
                     "pushed_at":"2026-10-30T12:00:00Z","default_branch":"main"}
                    """);
            return;
        }
        if (path.startsWith("/repos/coreyhaines31/marketingskills/commits")) {
            respond(exchange, 200,
                    "[{\"sha\":\"8f3c2a1d4e5f60718293a4b5c6d7e8f901234567\"}]");
            return;
        }
        if (path.equals("/repos/coreyhaines31/marketingskills/license")) {
            String text = "MIT License\n\nCopyright (c) 2025 Corey Haines\n\n"
                    + "Permission is hereby granted, free of charge, to any person obtaining a copy.";
            // No MIME line breaks: the adapter decodes with the MIME decoder, but this value is
            // embedded in a JSON string, where a raw newline would make the response unreadable.
            String encoded = Base64.getEncoder().encodeToString(
                    text.getBytes(StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"content":"%s",
                     "path":"LICENSE",
                     "html_url":"https://github.com/coreyhaines31/marketingskills/blob/main/LICENSE",
                     "license":{"spdx_id":"MIT","name":"MIT License"}}
                    """.formatted(encoded));
            return;
        }
        if (path.equals("/repos/acme/skills")) {
            respond(exchange, 200, """
                    {"stargazers_count":1,"forks_count":0,"pushed_at":"2026-10-30T12:00:00Z"}
                    """);
            return;
        }
        if (path.startsWith("/repos/acme/skills/commits")) {
            respond(exchange, 200, "[{\"sha\":\"aaaaaaa\"}]");
            return;
        }
        if (path.equals("/repos/acme/skills/contents/skills/ab-testing")) {
            respond(exchange, 200, """
                    [{"name":"LICENSE","path":"skills/ab-testing/LICENSE"}]
                    """);
            return;
        }
        if (path.equals("/repos/acme/skills/contents/skills/ab-testing/LICENSE")) {
            respond(exchange, 200, licenseBody("skills/ab-testing/LICENSE",
                    "Apache License\nVersion 2.0, January 2004\n\nCopyright (c) 2025 Acme"));
            return;
        }
        if (path.equals("/repos/acme/skills/license")) {
            respond(exchange, 200, licenseBody("LICENSE",
                    "MIT License\n\nPermission is hereby granted, free of charge."));
            return;
        }
        if (path.equals("/repos/beta/skills")) {
            respond(exchange, 200, """
                    {"stargazers_count":1,"forks_count":0,"pushed_at":"2026-10-30T12:00:00Z"}
                    """);
            return;
        }
        if (path.startsWith("/repos/beta/skills/commits")) {
            respond(exchange, 200, "[{\"sha\":\"bbbbbbb\"}]");
            return;
        }
        if (path.equals("/repos/beta/skills/contents/skills/ab-testing")) {
            respond(exchange, 200, "[]");
            return;
        }
        if (path.equals("/repos/beta/skills/contents/skills")) {
            respond(exchange, 200, """
                    [{"name":"LICENSE","path":"skills/LICENSE"}]
                    """);
            return;
        }
        if (path.equals("/repos/beta/skills/contents/skills/LICENSE")) {
            respond(exchange, 200, licenseBody("skills/LICENSE",
                    "Apache License\nVersion 2.0, January 2004\n\nCopyright (c) 2025 Beta"));
            return;
        }
        respond(exchange, 404, "{}");
    }

    private static String licenseBody(String path, String text) {
        String encoded = Base64.getEncoder().encodeToString(
                text.getBytes(StandardCharsets.UTF_8));
        return """
                {"content":"%s","path":"%s",
                 "html_url":"https://github.com/example/blob/main/%s",
                 "license":{"spdx_id":"NOASSERTION","name":"Other"}}
                """.formatted(encoded, path, path);
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
