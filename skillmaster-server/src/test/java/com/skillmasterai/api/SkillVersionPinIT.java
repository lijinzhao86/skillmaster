package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * §4.1's version pin, end to end: the three spellings an address accepts, what the response reports
 * about the version it resolved, and above all the failure this whole addressing change exists to
 * remove.
 *
 * <p>That failure is {@code aManifestStaysReadableAfterSomebodyPublishesAgain}. Every read endpoint
 * used to resolve {@code current_version_id} on its own, so a publish landing between "read the
 * manifest" and "fetch a file" silently swapped the bytes underneath the client — each request was
 * individually valid, so nothing reported it. The fix is not a session or a lock: the manifest
 * carries the version in every URI it hands out, so a client that follows them never asks for
 * {@code latest} again.
 *
 * <p>The skills here are published through the API rather than inserted, because the version numbers
 * are the subject: they are allocated by the server, and a fixture that wrote its own would prove
 * nothing about what a client receives.
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillVersionPinIT extends AbstractIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The fixture's address: the slug comes from V2's seed, the name from the zip. */
    private static final String ADDRESS = "/api/v1/skills/demo/pinned";

    private final byte[] first = zip("pinned", "第一版", "notes one");
    private final byte[] second = zip("pinned", "第二版", "notes two");

    @Test
    void anUnversionedAddressFollowsTheCurrentVersion() {
        publish(first);
        publish(second);

        JsonNode detail = JSON.readTree(get(ADDRESS, token()).body());

        assertThat(detail.get("version").get("number").asInt()).isEqualTo(2);
        assertThat(detail.get("version").get("is_latest").asBoolean())
                .as("the bare address resolves to the current version, and says that is what it did")
                .isTrue();
        assertThat(textOf(getBytes(bodyUriOf(detail), token()))).contains("第二版");
    }

    @Test
    void aNumberedAddressServesThatVersionAndReportsItIsNotCurrent() {
        publish(first);
        publish(second);

        JsonNode detail = JSON.readTree(get(ADDRESS + "@1", token()).body());

        assertThat(detail.get("version").get("number").asInt()).isEqualTo(1);
        assertThat(detail.get("version").get("is_latest").asBoolean())
                .as("a client reading @1 must be able to tell it is not holding what latest would give")
                .isFalse();
        assertThat(textOf(getBytes(bodyUriOf(detail), token())))
                .as("and the pinned body really is version 1's, not the current one's")
                .contains("第一版");
    }

    @Test
    void aDigestAddressResolvesToTheSameVersionAsItsNumber() {
        String digest = JSON.readTree(publish(first).body()).get("version").get("digest").asText();
        publish(second);

        JsonNode byDigest = JSON.readTree(get(ADDRESS + "@" + digest, token()).body());
        JsonNode byNumber = JSON.readTree(get(ADDRESS + "@1", token()).body());

        assertThat(byDigest.get("version").get("number").asInt()).isEqualTo(1);
        assertThat(byDigest.get("version").get("digest").asText()).isEqualTo(digest);
        assertThat(byDigest.get("files"))
                .as("the digest is the identity and the number is an alias for it, so the two "
                        + "addresses must describe the same bytes")
                .isEqualTo(byNumber.get("files"));
    }

    @Test
    void aManifestStaysReadableAfterSomebodyPublishesAgain() {
        publish(first);
        JsonNode before = JSON.readTree(get(ADDRESS, token()).body());
        String bodyUri = bodyUriOf(before);
        String notesUri = uriOf(before, "references/notes.md");

        // Somebody else's publish lands between reading the manifest and following it. Nothing the
        // client did is wrong, and under the old addressing this is where the content changed.
        publish(second);

        assertThat(textOf(getBytes(bodyUri, token())))
                .as("the manifest's URIs are pinned, so a later publish cannot move what they name")
                .contains("第一版");
        assertThat(textOf(getBytes(notesUri, token())))
                .as("including the files, which is the case that used to go wrong silently")
                .isEqualTo("notes one");
        assertThat(textOf(getBytes(ADDRESS + "/body", token())))
                .as("while the bare address did move — which is what makes the pinned one worth having")
                .contains("第二版");
    }

    @Test
    void aPinnedVersionOfASoftDeletedSkillIsNotFound() {
        // ADR 0012's 后果 section: the version rows survive a soft delete, so a resolver that only
        // asked "does this version exist" would keep serving deleted content. Both conditions are
        // checked, and the skill's being live is the first of them.
        String id = JSON.readTree(publish(first).body()).get("id").asText();
        assertThat(get(ADDRESS + "@1", token()).statusCode()).isEqualTo(200);

        assertThat(send(request("/api/v1/skills/demo/pinned", token()).DELETE().build()).statusCode())
                .isEqualTo(204);

        assertThat(get(ADDRESS + "@1", token()).statusCode())
                .as("the version row is still in the database; the skill is not")
                .isEqualTo(404);
        assertThat(count("SELECT count(*) FROM skill_version WHERE skill_id = :id", Map.of("id", id)))
                .as("and this is why: a soft delete leaves every version in place")
                .isEqualTo(1);
    }

    @Test
    void everyAddressThatResolvesToNothingGivesTheSameAnswer() {
        publish(first);

        HttpResponse<String> noSuchVersion = get(ADDRESS + "@99", token());
        HttpResponse<String> noSuchDigest = get(ADDRESS + "@sha256:" + "0".repeat(64), token());
        HttpResponse<String> noSuchSkill = get("/api/v1/skills/demo/nothing-here", token());
        HttpResponse<String> notMine = get("/api/v1/skills/other/pinned", token());

        assertThat(noSuchVersion.statusCode()).isEqualTo(404);
        assertThat(noSuchVersion.body())
                .as("§4.1: a version that does not exist is the same nothing as a skill that does not, "
                        + "and as somebody else's namespace — one code, one status, indistinguishable")
                .isEqualTo(noSuchSkill.body())
                .isEqualTo(noSuchDigest.body())
                .isEqualTo(notMine.body());
    }

    @Test
    void aWriteAddressDoesNotAcceptAVersionSuffix() {
        // §4.3: a write acts on the skill, and a version is produced or moved by the operation rather
        // than named by the caller. So the suffix is not stripped — it is part of the name, and no
        // skill can hold the character (M5 reserves it), so the address names nothing.
        publish(first);
        publish(second);

        assertThat(send(request(ADDRESS + "@1", token()).DELETE().build()).statusCode())
                .isEqualTo(404);
        assertThat(get(ADDRESS, token()).statusCode())
                .as("and nothing was deleted on the way to that answer")
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private HttpResponse<String> publish(byte[] zip) {
        Multipart multipart = Multipart.create().file("file", "pinned.zip", zip);
        HttpResponse<String> response = send(request("/api/v1/skills", token())
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
        assertThat(response.statusCode()).as("publish failed: %s", response.body()).isEqualTo(201);
        return response;
    }

    /** One skill, wrapped in a directory named after it, with content that differs per version. */
    private static byte[] zip(String name, String marker, String notes) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(name + "/SKILL.md",
                "---\nname: " + name + "\ndescription: a skill for the pinning tests\n---\n"
                        + marker + "\n");
        files.put(name + "/references/notes.md", notes);
        return Zips.ofText(files);
    }

    /** The pinned URI the manifest advertises for one file — what a client is told to fetch. */
    private static String uriOf(JsonNode detail, String relpath) {
        for (JsonNode file : detail.get("files")) {
            if (file.get("relpath").asText().equals(relpath)) {
                return file.get("uri").asText();
            }
        }
        throw new AssertionError(relpath + " is not in the manifest: " + detail);
    }

    private static String bodyUriOf(JsonNode detail) {
        return detail.get("resources").get("body").asText();
    }

    private static String textOf(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}
