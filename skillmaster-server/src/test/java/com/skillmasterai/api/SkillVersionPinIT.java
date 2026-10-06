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
 * <p><strong>The versions are submitted and published for real, in that order and on two planes</strong>
 * (ADR 0031). The numbers are part of what is under test — they are allocated by the server, and a
 * fixture that wrote its own would prove nothing about what a client receives — and so is the
 * pointer, which since the split moves only when somebody publishes. A row inserted directly could
 * be made to look right while the two steps that produce it were broken.
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillVersionPinIT extends AbstractAccountIT {

    private final byte[] first = zip("pinned", "第一版", "notes one");
    private final byte[] second = zip("pinned", "第二版", "notes two");

    /** The one account this test acts as, on both planes; see {@link #publish}. */
    private String username;
    private String token;

    @BeforeEach
    void signUp() {
        username = randomUsername();
        token = tokenFor(registerAndSignIn(username, randomPhone()));
    }

    @Test
    void anUnversionedAddressFollowsTheCurrentVersion() {
        publish(first);
        publish(second);

        JsonNode detail = JSON.readTree(get(address(), token).body());

        assertThat(detail.get("version").get("number").asInt()).isEqualTo(2);
        assertThat(detail.get("version").get("is_latest").asBoolean())
                .as("the bare address resolves to the current version, and says that is what it did")
                .isTrue();
        assertThat(textOf(getBytes(bodyUriOf(detail), token))).contains("第二版");
    }

    @Test
    void aNumberedAddressServesThatVersionAndReportsItIsNotCurrent() {
        publish(first);
        publish(second);

        JsonNode detail = JSON.readTree(get(address() + "@1", token).body());

        assertThat(detail.get("version").get("number").asInt()).isEqualTo(1);
        assertThat(detail.get("version").get("is_latest").asBoolean())
                .as("a client reading @1 must be able to tell it is not holding what latest would give")
                .isFalse();
        assertThat(textOf(getBytes(bodyUriOf(detail), token)))
                .as("and the pinned body really is version 1's, not the current one's")
                .contains("第一版");
    }

    @Test
    void aDigestAddressResolvesToTheSameVersionAsItsNumber() {
        String digest = publish(first).get("version").get("digest").asText();
        publish(second);

        JsonNode byDigest = JSON.readTree(get(address() + "@" + digest, token).body());
        JsonNode byNumber = JSON.readTree(get(address() + "@1", token).body());

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
        JsonNode before = JSON.readTree(get(address(), token).body());
        String bodyUri = bodyUriOf(before);
        String notesUri = uriOf(before, "references/notes.md");

        // Somebody else's publish lands between reading the manifest and following it. Nothing the
        // client did is wrong, and under the old addressing this is where the content changed.
        publish(second);

        assertThat(textOf(getBytes(bodyUri, token)))
                .as("the manifest's URIs are pinned, so a later publish cannot move what they name")
                .contains("第一版");
        assertThat(textOf(getBytes(notesUri, token)))
                .as("including the files, which is the case that used to go wrong silently")
                .isEqualTo("notes one");
        assertThat(textOf(getBytes(address() + "/body", token)))
                .as("while the bare address did move — which is what makes the pinned one worth having")
                .contains("第二版");
    }

    /**
     * ADR 0031's leak, at the address level: a draft submitted after the skill went live.
     *
     * <p>This is the case the published-only predicate exists for, and it is not the pointer's. The
     * skill is live; {@code @1} resolves; and {@code @2} names a version that exists, is perfectly
     * well-formed, and nobody has approved. Serving it would hand a consumer content the author has
     * not published yet — which is precisely what "publishing is a separate act" is supposed to mean.
     */
    @Test
    void aDraftIsNotAddressableEvenWhenTheSkillIsLive() {
        publish(first);
        assertThat(submit(second).statusCode()).as("the second version is submitted, not published")
                .isEqualTo(201);

        assertThat(get(address() + "@1", token).statusCode())
                .as("the published version is still there")
                .isEqualTo(200);
        assertThat(get(address() + "@2", token).statusCode())
                .as("and the draft is not addressable")
                .isEqualTo(404);
        assertThat(textOf(getBytes(address() + "/body", token)))
                .as("while the bare address still gives what was published")
                .contains("第一版");
    }

    /**
     * A pin on a skill nothing has been published from resolves to nothing, not to the newest draft.
     *
     * <p>The case the author plane's fallback exists for — a skill made only of drafts still has to
     * show its author something — and the one it must not leak into the consumption plane. On this
     * plane nothing is published, so neither is anything readable, whatever the address says.
     */
    @Test
    void aPinnedAddressOnASkillNothingIsPublishedFromIsNotFound() {
        assertThat(submit(first).statusCode()).isEqualTo(201);
        assertThat(submit(second).statusCode()).isEqualTo(201);

        assertThat(get(address(), token).statusCode()).as("nothing is live, so latest is nothing").isEqualTo(404);
        assertThat(get(address() + "@1", token).statusCode())
                .as("the draft is not addressable here, and neither is it addressable by number")
                .isEqualTo(404);
        assertThat(get(address() + "@2", token).statusCode()).isEqualTo(404);
    }

    @Test
    void aPinnedVersionOfASoftDeletedSkillIsNotFound() {
        // ADR 0012's 后果 section: the version rows survive a soft delete, so a resolver that only
        // asked "does this version exist" would keep serving deleted content. Both conditions are
        // checked, and the skill's being live is the first of them.
        String id = publish(first).get("id").asText();
        assertThat(get(address() + "@1", token).statusCode()).isEqualTo(200);

        assertThat(send(request(address(), token).DELETE().build()).statusCode()).isEqualTo(204);

        assertThat(get(address() + "@1", token).statusCode())
                .as("the version row is still in the database; the skill is not")
                .isEqualTo(404);
        assertThat(count("SELECT count(*) FROM skill_version WHERE skill_id = :id", Map.of("id", id)))
                .as("and this is why: a soft delete leaves every version in place")
                .isEqualTo(1);
    }

    @Test
    void everyAddressThatResolvesToNothingGivesTheSameAnswer() {
        publish(first);

        HttpResponse<String> noSuchVersion = get(address() + "@99", token);
        HttpResponse<String> noSuchDigest = get(address() + "@sha256:" + "0".repeat(64), token);
        HttpResponse<String> noSuchSkill =
                get("/api/v1/skills/" + username + "/nothing-here", token);
        HttpResponse<String> notMine = get("/api/v1/skills/other/pinned", token);

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

        assertThat(send(request(address() + "@1", token).DELETE().build()).statusCode())
                .isEqualTo(404);
        assertThat(get(address(), token).statusCode())
                .as("and nothing was deleted on the way to that answer")
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private String address() {
        return "/api/v1/skills/" + username + "/pinned";
    }

    /**
     * Both halves of ADR 0031: the content goes up over the API as a draft, and a person makes it
     * live in the browser. Returns the submission's response body, which carries the server-allocated
     * number and digest these tests are about.
     */
    private JsonNode publish(byte[] zip) {
        HttpResponse<String> submitted = submit(zip);
        assertThat(submitted.statusCode()).as("submit failed: %s", submitted.body()).isEqualTo(201);
        JsonNode body = JSON.readTree(submitted.body());
        makeLive(body.get("version").get("number").asInt());
        return body;
    }

    private HttpResponse<String> submit(byte[] zip) {
        Multipart multipart = Multipart.create().file("file", "pinned.zip", zip);
        return send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }

    private void makeLive(int number) {
        HttpResponse<String> published = webPost("/web/skills/" + username + "/pinned/publish",
                json(Map.of("number", number)));
        assertThat(published.statusCode()).as("publish failed: %s", published.body()).isEqualTo(200);
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
