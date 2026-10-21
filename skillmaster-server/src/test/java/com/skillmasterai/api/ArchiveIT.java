package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * A version as one zip (ADR 0068): what is in it, what it is called, and who may ask for it.
 *
 * <p>The fixture is made through the product's own paths — submit on the API plane, publish on the
 * browser plane — so the archive is built over rows the pipeline wrote, not over rows a test
 * inserted.
 */
@Sql("/sql/truncate-business-tables.sql")
class ArchiveIT extends AbstractAccountIT {

    private static final String BASE = "/web/skills";

    private static final String SKILL_MD = """
            ---
            name: pdf-tools
            title: PDF tools
            description: Extract and merge PDFs
            ---
            # PDF tools
            """;

    private String username;
    private String token;

    @BeforeEach
    void signUp() {
        username = randomUsername();
        token = tokenFor(registerAndSignIn(username, randomPhone()));
    }

    @Test
    void aPublishedVersionComesBackAsOneZipWithItsMetadata() throws IOException {
        String code = submitAndPublish();

        HttpResponse<byte[]> response = getBytes(authorArchive(), token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
                .startsWith("application/zip");
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_DISPOSITION).orElse(""))
                .contains("pdf-tools@1.2.3.zip");
        // The digest the author plane reports is the manifest's; the ETag is that same value in
        // its quoted, prefixed spelling. Recomputing it from the archive is what T1 does in the CLI.
        String digest = body(get("/api/v1/author/skills/" + username
                + "/pdf-tools/versions/1.2.3", token)).get("version").get("digest").asText();
        assertThat(response.headers().firstValue(HttpHeaders.ETAG)).hasValue("\"" + digest + "\"");

        Map<String, byte[]> entries = unzip(response.body());
        assertThat(entries.keySet()).containsExactlyInAnyOrder(
                "SKILL.md", "references/checklist.md", ".skillmaster/metadata.json");
        assertThat(new String(entries.get("references/checklist.md"), StandardCharsets.UTF_8))
                .isEqualTo("checklist");
        JsonNode sidecar = JSON.readTree(entries.get(".skillmaster/metadata.json"));
        assertThat(sidecar.get("schema_version").asInt()).isEqualTo(1);
        assertThat(sidecar.get("version").get("name").asText()).isEqualTo("初始版本");
        assertThat(code).isNotBlank();

        HttpResponse<String> detail = get("/api/v1/author/skills/" + username + "/pdf-tools/versions/1.2.3", token);
        assertThat(detail.statusCode()).as("detail: %s", detail.body()).isEqualTo(200);
        HttpResponse<byte[]> byNumber = getBytes(authorSkillBase() + "/versions/1.2.3/archive", token);
        assertThat(byNumber.statusCode())
                .as("by version number: %s", new String(byNumber.body(), StandardCharsets.UTF_8))
                .isEqualTo(200);
    }

    @Test
    void aDraftIsAddressedAndNamedByItsVersionCode() {
        String code = submitDraft();

        HttpResponse<byte[]> response = getBytes(authorSkillBase() + "/versions/" + code + "/archive", token);
        assertThat(response.statusCode()).as("body: %s", new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_DISPOSITION).orElse(""))
                .contains("pdf-tools@" + code + ".zip");
        assertThat(unzipQuietly(response.body()).keySet())
                .containsExactlyInAnyOrder("SKILL.md", "references/checklist.md",
                        ".skillmaster/metadata.json");
    }

    @Test
    void aDiscardedVersionIsNotDownloadable() {
        String code = submitDraft();
        assertThat(webPost(BASE + "/" + username + "/pdf-tools/discard",
                json(Map.of("version", code))).statusCode()).isEqualTo(200);

        HttpResponse<String> response = get(authorSkillBase() + "/versions/" + code + "/archive", token);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).get("error").get("code").asText())
                .isEqualTo("invalid_version_state");
    }

    @Test
    void theBrowserPlaneServesTheSameArchive() {
        submitAndPublish();

        HttpResponse<byte[]> response = browser.getBytes(
                uri(BASE + "/" + username + "/pdf-tools@1.2.3/archive"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(unzipQuietly(response.body()).keySet()).contains("SKILL.md");
    }

    /* ------------------------------------------------------------------ fixtures */

    private String authorArchive() {
        return authorSkillBase() + "/archive";
    }

    private String authorSkillBase() {
        return "/api/v1/author/skills/" + username + "/pdf-tools";
    }

    private String submitDraft() {
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(Map.of(
                        "SKILL.md", SKILL_MD,
                        "references/checklist.md", "checklist")))
                .field("name", "初始版本");
        HttpResponse<String> submitted = send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher()).build());
        assertThat(submitted.statusCode()).as("submit failed: %s", submitted.body()).isEqualTo(201);
        return body(submitted).get("version").get("draft_id").asText();
    }

    private String submitAndPublish() {
        String code = submitDraft();
        HttpResponse<String> published = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("version", code, "release_name", "1.2.3")));
        assertThat(published.statusCode()).as("publish failed: %s", published.body()).isEqualTo(200);
        return code;
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private static Map<String, byte[]> unzipQuietly(byte[] zip) {
        try {
            return unzip(zip);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
