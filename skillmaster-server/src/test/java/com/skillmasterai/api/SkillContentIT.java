package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.support.AbstractIT;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;

/**
 * L2 and L3: the bytes themselves, and the rule that no path ever reaches a filesystem.
 *
 * <p>These run against a real Tomcat on a random port because a good part of what they assert is
 * the container's behaviour, not ours — how an encoded slash is handled, whether a traversal is
 * normalised before routing, and what happens when Spring Security's firewall refuses a request
 * before any controller is chosen. MockMvc has no opinion about any of that.
 *
 * <p>The traversal cases assert a <em>set</em> of acceptable answers rather than one status. Which
 * of 400 or 404 comes back depends on which layer notices first — the firewall, the router, or the
 * manifest lookup — and pinning the layer would make this test fail on a Tomcat upgrade for no
 * reason. What must never happen is 200 (a leaked file) or 5xx (an unhandled rejection).
 *
 * <p>The fixture publishes one version, so every address here is unversioned and resolves to it.
 * What a pinned address does, and that the URIs the manifest advertises actually resolve, is
 * {@code SkillVersionPinIT}'s subject.
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillContentIT extends AbstractIT {

    private static final String DEMO_NAMESPACE_ID = "01M3HTGC79VYJGM8BFXHX2QYNH";
    private static final String DEMO_USER_ID = "01M3HTG7GCCVBGRPAFFSVSF12W";
    private static final String OTHER_NAMESPACE_ID = "01M3HTGC79CHKDB4Q0T2JMRCWV";
    private static final String OTHER_USER_ID = "01M3HTG7GDQ71Q28CCP7J0HM8T";

    /** The fixture's address: the namespace slug comes from V2's seed, the name from the fixture. */
    private static final String DEMO_SKILL = "/api/v1/skills/demo/pdf-tools";
    private static final String OTHER_SKILL = "/api/v1/skills/other/pdf-tools";

    /** Non-ASCII on purpose: a charset mistake on a text type shows up as mojibake, not as a diff. */
    private static final String SKILL_MD = """
            ---
            name: pdf-tools
            description: 提取与合并 PDF
            ---
            # PDF 工具

            取正文时引用 `references/checklist.md`。
            """;

    private static final String CHECKLIST = "# Checklist\n\n- [ ] 第一步\n";

    @Test
    void servesTheBodyByteForByte() {
        insertSkill();

        HttpResponse<byte[]> response = getBytes(DEMO_SKILL + "/body");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("what was published is what comes back — frontmatter included, nothing rewritten")
                .isEqualTo(SKILL_MD.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void declaresTheBodyAsUtf8Markdown() {
        // Not decoration: HTTP gives text/* a default charset of ISO-8859-1, so without this a
        // client taking the default at its word renders the Chinese above as mojibake.
        insertSkill();

        HttpResponse<byte[]> response = getBytes(DEMO_SKILL + "/body");

        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow())
                .startsWith("text/markdown")
                .contains("UTF-8");
    }

    @Test
    void servesASingleFileByteForByte() {
        insertSkill();

        HttpResponse<byte[]> response = getBytes(DEMO_SKILL + "/files/references/checklist.md");

        assertThat(response.statusCode())
                .as("status %s, headers %s, body: %s", response.statusCode(), response.headers(),
                        new String(response.body(), StandardCharsets.UTF_8))
                .isEqualTo(200);
        assertThat(response.body()).isEqualTo(CHECKLIST.getBytes(StandardCharsets.UTF_8));
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow())
                .startsWith("text/markdown");
    }

    @Test
    void servesSkillMdThroughL3AsWellAsThroughL2() {
        // SKILL.md is a manifest entry like any other (§1.3): it is not a special case that lives
        // outside the file set, and L3 must reach it too.
        insertSkill();

        assertThat(getBytes(DEMO_SKILL + "/files/SKILL.md").body())
                .isEqualTo(getBytes(DEMO_SKILL + "/body").body());
    }

    @Test
    void guessesTheTypeFromTheExtensionAndFallsBackToOctetStream() {
        insertSkill();

        assertThat(contentTypeOf("references/checklist.md")).startsWith("text/markdown");
        assertThat(contentTypeOf("notes.json")).isEqualTo("application/json;charset=UTF-8");
        assertThat(contentTypeOf("logo.png")).isEqualTo("image/png");
        assertThat(contentTypeOf("Makefile"))
                .as("no extension is not a guess: octet-stream is the honest answer")
                .isEqualTo("application/octet-stream");
        assertThat(contentTypeOf("archive.v1"))
                .as("an unknown extension is not treated as one we know")
                .isEqualTo("application/octet-stream");
    }

    @Test
    void aFileThatIsNotInTheManifestIsNotFound() {
        // §4.1 gives this its own code rather than folding it into skill_not_found: the address did
        // resolve — the skill and the version both exist and the caller may read them — and only the
        // file is absent. A client written against that table can match it.
        insertSkill();

        HttpResponse<byte[]> response = getBytes(DEMO_SKILL + "/files/references/missing.md");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .contains("\"code\":\"file_not_found\"");
    }

    @Test
    void anotherUsersFileIsNotFound() {
        insertSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID);

        HttpResponse<byte[]> response = getBytes(OTHER_SKILL + "/files/SKILL.md");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .as("skill_not_found, never file_not_found: the second would confirm the skill exists")
                .contains("\"code\":\"skill_not_found\"");
        assertThat(getBytes(OTHER_SKILL + "/body").statusCode()).isEqualTo(404);
    }

    @Test
    void traversalAttemptsNeverServeAFileAndNeverCrash() {
        insertSkill();
        // All percent-encoded where the raw character would not survive URI construction: a real
        // client cannot put a backslash in a path either, so the encoded form is the one that
        // actually reaches a server.
        String[] attempts = {
                "../SKILL.md",
                "../../etc/passwd",
                "references/../../SKILL.md",
                "/etc/passwd",
                "..%2fSKILL.md",
                "references%2f..%2f..%2fSKILL.md",
                "%2e%2e%2fSKILL.md",
                "..%5cSKILL.md",
                "references%5c..%5c..%5cSKILL.md",
        };

        for (String attempt : attempts) {
            HttpResponse<byte[]> response = getBytes(DEMO_SKILL + "/files/" + attempt);
            assertThat(response.statusCode())
                    .as("%s must not be served and must not crash the server", attempt)
                    .isIn(400, 404);
            assertThat(new String(response.body(), StandardCharsets.UTF_8))
                    .as("%s must not leak anything", attempt)
                    .doesNotContain("root:");
        }
    }

    @Test
    void anEmptyPathAfterFilesIsNotAFreeLookup() {
        // `{*relpath}` takes zero or more characters, so this request does route — with an empty
        // relpath, which must be looked up like any other and find nothing.
        //
        // Asserted by code rather than by status alone, which is what the traversal cases above
        // settle for deliberately: "404" cannot tell a route that never matched from a manifest
        // lookup that found nothing, and those are different failures to diagnose.
        insertSkill();

        HttpResponse<byte[]> response = getBytes(DEMO_SKILL + "/files/");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .as("it reached the manifest and found nothing, rather than never being routed")
                .contains("\"code\":\"file_not_found\"");
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** The extra files every fixture skill carries, so the media-type table can be exercised. */
    private static final Map<String, byte[]> EXTRA_FILES = Map.of(
            "notes.json", "{}".getBytes(StandardCharsets.UTF_8),
            "logo.png", new byte[] {(byte) 0x89, 'P', 'N', 'G'},
            "Makefile", "all:\n".getBytes(StandardCharsets.UTF_8),
            "archive.v1", "opaque".getBytes(StandardCharsets.UTF_8));

    private String contentTypeOf(String relpath) {
        HttpResponse<byte[]> response = getBytes(DEMO_SKILL + "/files/" + relpath);
        assertThat(response.statusCode()).as("%s should exist", relpath).isEqualTo(200);
        return response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow();
    }

    private void insertSkill() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID);
    }

    /** Inserts a live skill whose manifest holds SKILL.md, a nested reference, and type fixtures. */
    private void insertSkill(String namespaceId, String userId) {
        String skillId = Ulid.generate();
        String versionId = Ulid.generate();
        String at = "2026-09-28T00:00:00Z";

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", SKILL_MD.getBytes(StandardCharsets.UTF_8));
        files.put("references/checklist.md", CHECKLIST.getBytes(StandardCharsets.UTF_8));
        files.putAll(EXTRA_FILES);

        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, current_version_id, created_by, created_at, updated_at)
                VALUES (:id, :namespace, 'pdf-tools', 'PDF tools', 'Extract and merge PDFs',
                        '{"name":"pdf-tools","description":"Extract and merge PDFs"}',
                        'private', :version, :user, :at, :at)
                """)
                .param("id", skillId).param("namespace", namespaceId).param("version", versionId)
                .param("user", userId).param("at", at)
                .update();

        long total = files.values().stream().mapToLong(f -> f.length).sum();
        jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, digest, file_count, total_bytes,
                                           changelog, source, published_by, published_at)
                VALUES (:id, :skill, 1, :digest, :count, :total, '', 'zip', :user, :at)
                """)
                .param("id", versionId).param("skill", skillId)
                .param("digest", sha256Hex(("digest of " + skillId).getBytes(StandardCharsets.UTF_8)))
                .param("count", files.size()).param("total", total)
                .param("user", userId).param("at", at)
                .update();

        files.forEach((relpath, bytes) -> {
            String sha = insertBlob(bytes);
            jdbc.sql("""
                    INSERT INTO version_file (version_id, relpath, blob_sha256, size, is_binary)
                    VALUES (:version, :relpath, :sha, :size, 0)
                    """)
                    .param("version", versionId).param("relpath", relpath).param("sha", sha)
                    .param("size", bytes.length).update();
        });
    }

    private String insertBlob(byte[] bytes) {
        String sha = sha256Hex(bytes);
        jdbc.sql("INSERT INTO blob (sha256, size, created_at) VALUES (:sha, :size, :at)")
                .param("sha", sha).param("size", bytes.length).param("at", Timestamps.now())
                .update();
        jdbc.sql("INSERT INTO blob_content (sha256, bytes) VALUES (:sha, :bytes)")
                .param("sha", sha).param("bytes", bytes).update();
        return sha;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE", e);
        }
    }
}
