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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
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


    /**
     * The executable bit crosses the archive in both directions (v51).
     *
     * <p>It is the one property a plain copy loses, and losing it is what turns `./run.sh` into
     * "permission denied" on the user's machine — so the download path is where the promise is
     * checked, not the database.
     */
    @Test
    void aScriptUnderScriptsIsExecutableInTheArchive() throws IOException {
        String code = submitDraft(Zips.ofTextWithExecutable(Map.of(
                "SKILL.md", SKILL_MD,
                "scripts/run.sh", "#!/bin/sh\necho hi\n"), "scripts/run.sh"));
        HttpResponse<String> published = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("version", code, "release_name", "1.2.3")));
        assertThat(published.statusCode()).as("publish failed: %s", published.body()).isEqualTo(200);

        HttpResponse<byte[]> response = getBytes(authorArchive(), token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(unixMode(response.body(), "scripts/run.sh") & 0111)
                .as("a file under scripts/ is executable in the archive (ADR 0166)")
                .isEqualTo(0111);
        assertThat(unixMode(response.body(), "SKILL.md") & 0111)
                .as("an ordinary file stays ordinary")
                .isZero();
    }

    /**
     * The archive reads the bit off the path, not off {@code version_file.mode} (ADR 0166).
     *
     * <p>Every write path derives that column too, so this row is one no product path produces. It is
     * written here because the archive is the delivery boundary: if a path is ever added that forgets
     * to derive, the person who downloads must not be the one who finds out.
     */
    @Test
    void theArchiveDoesNotTrustAStoredMode() throws IOException {
        String code = submitDraft(Zips.ofText(Map.of(
                "SKILL.md", SKILL_MD,
                "scripts/run.sh", "#!/bin/sh\necho hi\n")));
        HttpResponse<String> published = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("version", code, "release_name", "1.2.3")));
        assertThat(published.statusCode()).as("publish failed: %s", published.body()).isEqualTo(200);

        int rows = jdbc.sql("""
                UPDATE version_file SET mode = 420
                 WHERE relpath = 'scripts/run.sh'
                   AND version_id = (SELECT v.id FROM skill_version v
                                       JOIN skill s ON s.id = v.skill_id
                                       JOIN namespace n ON n.id = s.namespace_id
                                      WHERE n.slug = :slug AND s.name = 'pdf-tools'
                                        AND v.version = '1.2.3')
                """).param("slug", username).update();
        assertThat(rows).as("the fixture must have a script to disagree about").isEqualTo(1);

        HttpResponse<byte[]> response = getBytes(authorArchive(), token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(unixMode(response.body(), "scripts/run.sh") & 0111)
                .as("the relpath decides, whatever the row says")
                .isEqualTo(0111);
    }
    @Test
    void anArchiveCarriesTheLicenseSidecarBesideTheContent() throws IOException {
        submitAndPublish();
        String versionId = jdbc.sql("""
                SELECT v.id FROM skill_version v
                  JOIN skill s ON s.id = v.skill_id
                  JOIN namespace n ON n.id = s.namespace_id
                 WHERE n.slug = :slug AND s.name = 'pdf-tools' AND v.version = '1.2.3'
                """)
                .param("slug", username)
                .query((rs, rowNum) -> rs.getString(1))
                .single();
        String skillId = jdbc.sql("""
                SELECT s.id FROM skill s JOIN namespace n ON n.id = s.namespace_id
                 WHERE n.slug = :slug AND s.name = 'pdf-tools'
                """)
                .param("slug", username)
                .query((rs, rowNum) -> rs.getString(1))
                .single();
        jdbc.sql("""
                INSERT INTO skill_source (id, skill_id, role, kind, locator, path, label, url,
                                          created_at)
                VALUES ('src-archive', :skill, 'origin', 'github', 'coreyhaines31/marketingskills',
                        'skills/pdf-tools', 'GitHub',
                        'https://github.com/coreyhaines31/marketingskills',
                        '2026-11-05T00:00:00Z')
                """).param("skill", skillId).update();
        jdbc.sql("""
                INSERT INTO skill_version_license (version_id, spdx, name, copyright, status,
                        license_text, source_path, source_url, detail, fetched_at)
                VALUES (:version, 'MIT', 'MIT License', 'Copyright (c) 2025 Corey Haines', 'ok',
                        'Permission is hereby granted, free of charge.', 'LICENSE',
                        'https://example.test/LICENSE', '{}', '2026-11-05T00:00:00Z')
                """).param("version", versionId).update();

        HttpResponse<byte[]> response = getBytes(authorArchive(), token);

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, byte[]> entries = unzip(response.body());
        assertThat(entries.keySet()).contains(
                ".skillmaster/license.json", ".skillmaster/LICENSE",
                ".skillmaster/attribution.md");
        JsonNode license = JSON.readTree(entries.get(".skillmaster/license.json"));
        assertThat(license.get("schema_version").asInt()).isEqualTo(1);
        assertThat(license.get("spdx").asText()).isEqualTo("MIT");
        assertThat(license.get("status").asText()).isEqualTo("ok");
        assertThat(license.get("origin").get("kind").asText()).isEqualTo("github");
        assertThat(new String(entries.get(".skillmaster/LICENSE"), StandardCharsets.UTF_8))
                .isEqualTo("Permission is hereby granted, free of charge.");
        String attribution = new String(entries.get(".skillmaster/attribution.md"),
                StandardCharsets.UTF_8);
        assertThat(attribution).contains("来源：GitHub · coreyhaines31/marketingskills")
                .contains("许可：MIT")
                .contains("版权：Copyright (c) 2025 Corey Haines");

        // The sidecar is metadata, not content: the digest the author plane reports is unchanged.
        JsonNode detail = body(get("/api/v1/author/skills/" + username
                + "/pdf-tools/versions/1.2.3", token));
        assertThat(detail.get("version").get("digest").asText()).startsWith("sha256:");
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
        return submitDraft(Zips.ofText(Map.of(
                "SKILL.md", SKILL_MD,
                "references/checklist.md", "checklist")));
    }

    private String submitDraft(byte[] zip) {
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", zip)
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

    /**
     * One entry's unix mode, read back out of the archive the download path served.
     *
     * <p>**Random access, not the streaming reader.** The mode lives in the central directory's
     * "version made by" field, which a streaming (`ZipArchiveInputStream`) pass never sees — it
     * reports 0 for every entry. `ZipFile` reads the directory, which is also what the Go client
     * does, so this is the side of the format the promise is about.
     */
    private static int unixMode(byte[] zip, String name) throws IOException {
        try (ZipFile file = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(zip))
                .get()) {
            for (ZipArchiveEntry entry : Collections.list(file.getEntries())) {
                if (entry.getName().equals(name)) {
                    return entry.getUnixMode();
                }
            }
        }
        throw new AssertionError("the archive has no entry named " + name);
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
