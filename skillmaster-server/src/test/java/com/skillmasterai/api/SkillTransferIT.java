package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * Importing a package on the browser plane, and the metadata sidecar that makes a round trip
 * possible (ADR 0069).
 *
 * <p>The sidecar cases are here rather than in {@code SkillUploadValidatorTest} because what is
 * under test is the submission pipeline's use of it — request-wins precedence, idempotence leaving
 * the stored name alone, and the ceiling excluding metadata from the content count.
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillTransferIT extends AbstractAccountIT {

    private static final String BASE = "/web/skills";

    private static final String SKILL_MD = """
            ---
            name: pdf-tools
            title: PDF tools
            description: Extract and merge PDFs
            ---
            # PDF tools
            """;

    private static final String SIDECAR = ".skillmaster/metadata.json";

    private String username;
    private String token;

    @BeforeEach
    void signUp() {
        username = randomUsername();
        token = tokenFor(registerAndSignIn(username, randomPhone()));
    }

    @Test
    void anImportAddsADraftAndReplaysIdentically() {
        seedSkill();

        Multipart first = Multipart.create()
                .file("file", "pdf-tools.zip", packageWith("references/notes.md", "notes"))
                .field("name", "导入的版本");
        HttpResponse<String> created = webImport(skillImportPath(), first);

        assertThat(created.statusCode()).as("import failed: %s", created.body()).isEqualTo(201);
        JsonNode version = body(created).get("version");
        assertThat(version.get("state").asText()).isEqualTo("draft");
        assertThat(version.get("name").isNull()).as("a draft has no version number").isTrue();
        assertThat(version.get("draft_name").asText()).isEqualTo("导入的版本");

        Multipart again = Multipart.create()
                .file("file", "pdf-tools.zip", packageWith("references/notes.md", "notes"))
                .field("name", "another name");
        HttpResponse<String> replay = webImport(skillImportPath(), again);

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(body(replay).get("created").asBoolean()).isFalse();
        assertThat(body(replay).get("version").get("draft_name").asText())
                .as("a replay answers with the stored row and does not rename it")
                .isEqualTo("导入的版本");
    }

    @Test
    void aNameMismatchNamesBothNames() {
        seedSkill();

        Multipart multipart = Multipart.create()
                .file("file", "other.zip", Zips.ofText(Map.of("SKILL.md", SKILL_MD
                        .replace("name: pdf-tools", "name: split"))))
                .field("name", "导入");

        HttpResponse<String> response = webImport(skillImportPath(), multipart);

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = body(response).get("error");
        assertThat(error.get("code").asText()).isEqualTo("invalid_upload");
        // Read by `field`, not by substring: three details travel together and the page looks each
        // one up by name (TD §接口 6 — the value is the issue, the field names which value it is).
        JsonNode details = error.get("details");
        assertThat(details.toString()).contains("name_mismatch");
        assertThat(issueOf(details, "address_name")).isEqualTo("pdf-tools");
        assertThat(issueOf(details, "content_name")).isEqualTo("split");
    }

    @Test
    void anExportedVersionComesBackAsTheSameDigest() {
        // T2's round trip at the interface: the archive the browser downloads is the package the
        // browser imports, and identical content must replay onto the version it came from.
        String code = seedSkill();
        HttpResponse<byte[]> archive = browser.getBytes(
                uri(BASE + "/" + username + "/pdf-tools@" + code + "/archive"));
        assertThat(archive.statusCode())
                .as("archive: %s", new String(archive.body(), StandardCharsets.UTF_8))
                .isEqualTo(200);
        String digest = body(webGet(BASE + "/" + username + "/pdf-tools@" + code))
                .get("version").get("digest").asText();

        Multipart replay = Multipart.create()
                .file("file", "pdf-tools.zip", archive.body())
                .field("name", "再导入");
        HttpResponse<String> response = webImport(skillImportPath(), replay);

        assertThat(response.statusCode()).as("import: %s", response.body()).isEqualTo(200);
        assertThat(body(response).get("created").asBoolean()).isFalse();
        assertThat(body(response).get("version").get("digest").asText()).isEqualTo(digest);
        // The stored name wins: the sidecar in the package says 「打底」, the request said 「再导入」,
        // and a replay does not overwrite what the version already is.
        assertThat(body(response).get("version").get("draft_name").asText()).isEqualTo("打底");
    }

    @Test
    void aNewNameCreatesTheSkillOnTheNamespaceRouteAndReplaysWithoutASecond() {
        String fresh = """
                ---
                name: fresh-skill
                description: A skill nobody had
                ---
                # Fresh
                """;
        Multipart multipart = Multipart.create()
                .file("file", "fresh.zip", Zips.ofText(Map.of("SKILL.md", fresh)))
                .field("name", "第一版");

        HttpResponse<String> created = webImport(BASE + "/import", multipart);
        assertThat(created.statusCode()).as("create: %s", created.body()).isEqualTo(201);
        assertThat(body(created).get("skill_created").asBoolean()).isTrue();
        assertThat(body(created).get("name").asText()).isEqualTo("fresh-skill");

        Multipart again = Multipart.create()
                .file("file", "fresh.zip", Zips.ofText(Map.of("SKILL.md", fresh)))
                .field("name", "第二版");
        HttpResponse<String> replayed = webImport(BASE + "/import", again);
        assertThat(replayed.statusCode()).as("replay: %s", replayed.body()).isEqualTo(200);
        assertThat(body(replayed).get("created").asBoolean()).isFalse();
        assertThat(body(webGet(BASE)).get("skills")).hasSize(1);
    }

    @Test
    void aMalformedSidecarNamesItsIssueAndItsField() {
        Multipart unknownField = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(Map.of("SKILL.md", SKILL_MD,
                        SIDECAR, "{\"schema_version\":1,\"tags\":[]}")))
                .field("name", "导入");
        HttpResponse<String> unknown = webImport(skillImportPath(), unknownField);
        assertThat(unknown.statusCode()).isEqualTo(400);
        JsonNode unknownDetails = body(unknown).get("error").get("details");
        assertThat(issueOf(unknownDetails, SIDECAR)).isEqualTo("metadata_unknown_field");
        assertThat(issueOf(unknownDetails, "metadata_field")).isEqualTo("tags");

        Multipart wrongType = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(Map.of("SKILL.md", SKILL_MD,
                        SIDECAR, "{\"schema_version\":1,\"version\":{\"name\":7}}")))
                .field("name", "导入");
        HttpResponse<String> typed = webImport(skillImportPath(), wrongType);
        assertThat(typed.statusCode()).isEqualTo(400);
        JsonNode typedDetails = body(typed).get("error").get("details");
        assertThat(issueOf(typedDetails, SIDECAR)).isEqualTo("metadata_field_invalid");
        assertThat(issueOf(typedDetails, "metadata_field")).isEqualTo("name");
    }

    @Test
    void thePackageNamesTheVersionWhenTheRequestDoesNot() {
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", packageWithSidecar("包里的名字", "包里的说明"))
                .withoutName();
        HttpResponse<String> submitted = submitOnApi(multipart);

        assertThat(submitted.statusCode()).as("submit failed: %s", submitted.body()).isEqualTo(201);
        assertThat(body(submitted).get("version").get("draft_name").asText())
                .isEqualTo("包里的名字");

        // Same content, a different name in the package: the stored row keeps its own name,
        // because a replay is not a rename.
        Multipart changed = Multipart.create()
                .file("file", "pdf-tools.zip", packageWithSidecar("换了个名字", null))
                .withoutName();
        HttpResponse<String> replay = submitOnApi(changed);

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(body(replay).get("version").get("draft_name").asText())
                .isEqualTo("包里的名字");
    }

    @Test
    void aFileAtTheReservedPathThatIsNotMetadataIsRefused() {
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(Map.of(
                        "SKILL.md", SKILL_MD,
                        SIDECAR, "this is not json")))
                .field("name", "导入");

        HttpResponse<String> response = submitOnApi(multipart);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).get("error").get("details").toString())
                .contains("reserved_path");
    }

    @Test
    void theSidecarDoesNotCountTowardTheFileCeiling() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", SKILL_MD);
        for (int i = 0; i < 511; i++) {
            files.put("references/file-" + i + ".md", "content " + i);
        }
        files.put(SIDECAR, "{\"schema_version\":1,\"version\":{\"name\":\"藏在元数据里\"}}");

        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(files))
                .withoutName();
        HttpResponse<String> submitted = submitOnApi(multipart);

        assertThat(submitted.statusCode())
                .as("512 content files plus metadata must be accepted: %s", submitted.body())
                .isEqualTo(201);
        assertThat(body(submitted).get("version").get("file_count").asInt()).isEqualTo(512);
    }

    @Test
    void aDeletedNameIsANamedConflict() {
        seedSkill();
        assertThat(webPost(BASE + "/" + username + "/pdf-tools/delete", "").statusCode())
                .isEqualTo(204);

        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", packageWith("references/other.md", "other"))
                .field("name", "导入");

        HttpResponse<String> response = webImport(BASE + "/import", multipart);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(body(response).get("error").get("code").asText())
                .isEqualTo("skill_deleted");
    }

    /* ------------------------------------------------------------------ fixtures */

    private String skillImportPath() {
        return BASE + "/" + username + "/pdf-tools/versions/import";
    }

    /** A skill whose only version is a draft — enough for the import routes to resolve. */
    /** Seeds a skill whose only version is a draft, and returns that draft's version code. */
    private String seedSkill() {
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(Map.of("SKILL.md", SKILL_MD)))
                .field("name", "打底");
        HttpResponse<String> response = submitOnApi(multipart);
        assertThat(response.statusCode()).isEqualTo(201);
        return body(response).get("version").get("draft_id").asText();
    }

    /** The issue code carried by the one detail whose field is {@code field}, or null. */
    private static String issueOf(JsonNode details, String field) {
        for (JsonNode detail : details) {
            if (field.equals(detail.get("field").asText())) {
                return detail.get("issue").asText();
            }
        }
        return null;
    }

    private HttpResponse<String> submitOnApi(Multipart multipart) {
        return send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher()).build());
    }

    private HttpResponse<String> webImport(String path, Multipart multipart) {
        return browser.postMultipart(uri(path), multipart.contentType(), multipart.publisher());
    }

    private static byte[] packageWith(String relpath, String text) {
        return Zips.ofText(Map.of("SKILL.md", SKILL_MD, relpath, text));
    }

    private static byte[] packageWithSidecar(String name, String note) {
        String notePart = note == null ? "" : ",\"note\":\"" + note + "\"";
        String sidecar = "{\"schema_version\":1,\"version\":{\"name\":\"" + name + "\""
                + notePart + "}}";
        return Zips.ofText(Map.of("SKILL.md", SKILL_MD, SIDECAR, sidecar));
    }
}
