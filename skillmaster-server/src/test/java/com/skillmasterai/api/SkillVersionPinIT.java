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
 * (ADR 0031). The version names are the author's — declared in {@code SKILL.md} and carried through
 * verbatim (ADR 0033) — and the pointer, which since the split moves only when somebody publishes, is
 * likewise what the server wrote. A row inserted directly could be made to look right while the two
 * steps that produce it were broken.
 *
 * <p>The nameless cases are here rather than in a unit test because the whole of a nameless version's
 * behaviour is on the wire: it is a version with no name, and the only thing that addresses it is the
 * digest the response reports (ADR 0033).
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillVersionPinIT extends AbstractAccountIT {

    private static final String FIRST_VERSION = "1.0.0";
    private static final String SECOND_VERSION = "2.0.0";

    private final byte[] first = zip("pinned", FIRST_VERSION, "第一版", "notes one");
    private final byte[] second = zip("pinned", SECOND_VERSION, "第二版", "notes two");

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

        assertThat(detail.get("version").get("name").asText()).isEqualTo(SECOND_VERSION);
        assertThat(detail.get("version").get("is_latest").asBoolean())
                .as("the bare address resolves to the current version, and says that is what it did")
                .isTrue();
        assertThat(textOf(getBytes(bodyUriOf(detail), token))).contains("第二版");
    }

    @Test
    void aNamedAddressServesThatVersionAndReportsItIsNotCurrent() {
        publish(first);
        publish(second);

        JsonNode detail = JSON.readTree(get(address() + "@" + FIRST_VERSION, token).body());

        assertThat(detail.get("version").get("name").asText()).isEqualTo(FIRST_VERSION);
        assertThat(detail.get("version").get("is_latest").asBoolean())
                .as("a client reading @1.0.0 must be able to tell it is not holding what latest "
                        + "would give")
                .isFalse();
        assertThat(textOf(getBytes(bodyUriOf(detail), token)))
                .as("and the pinned body really is 1.0.0's, not the current one's")
                .contains("第一版");
    }

    /**
     * A version name carrying SemVer build metadata survives the whole round trip.
     *
     * <p>The grammar allows {@code +build}, and a suffix the transport cannot carry is not a grammar
     * at all — so this is the half a unit test cannot answer. Two things could refuse it: a `+` is a
     * *space* in a query string, and a servlet firewall is entitled to reject encoded ones. Reading
     * the body back through the URI the server itself minted is the point: that is the path a client
     * follows without ever assembling an address of its own.
     */
    @Test
    void aVersionCarryingBuildMetadataSurvivesTheAddress() {
        publish(zip("pinned", "1.0.0+build.1", "第一版", "notes one"));

        JsonNode detail = JSON.readTree(get(address() + "@1.0.0+build.1", token).body());

        assertThat(detail.get("version").get("name").asText()).isEqualTo("1.0.0+build.1");
        assertThat(textOf(getBytes(bodyUriOf(detail), token)))
                .as("and the URI the server minted for it is one it can serve back")
                .contains("第一版");
    }

    @Test
    void aDigestAddressResolvesToTheSameVersionAsItsName() {
        String digest = publish(first).get("version").get("digest").asText();
        publish(second);

        JsonNode byDigest = JSON.readTree(get(address() + "@" + digest, token).body());
        JsonNode byName = JSON.readTree(get(address() + "@" + FIRST_VERSION, token).body());

        assertThat(byDigest.get("version").get("name").asText()).isEqualTo(FIRST_VERSION);
        assertThat(byDigest.get("version").get("digest").asText()).isEqualTo(digest);
        assertThat(byDigest.get("files"))
                .as("the digest is the identity and the name is an alias for it, so the two "
                        + "addresses must describe the same bytes")
                .isEqualTo(byName.get("files"));
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
     * skill is live; {@code @1.0.0} resolves; and {@code @2.0.0} names a version that exists, is
     * perfectly well-formed, and nobody has approved. Serving it would hand a consumer content the
     * author has not published yet — which is precisely what "publishing is a separate act" is
     * supposed to mean.
     */
    @Test
    void aDraftIsNotAddressableEvenWhenTheSkillIsLive() {
        publish(first);
        assertThat(submit(second).statusCode()).as("the second version is submitted, not published")
                .isEqualTo(201);

        assertThat(get(address() + "@" + FIRST_VERSION, token).statusCode())
                .as("the published version is still there")
                .isEqualTo(200);
        assertThat(get(address() + "@" + SECOND_VERSION, token).statusCode())
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

        assertThat(get(address(), token).statusCode()).as("nothing is live, so latest is nothing")
                .isEqualTo(404);
        assertThat(get(address() + "@" + FIRST_VERSION, token).statusCode())
                .as("the draft is not addressable here, and neither is it addressable by name")
                .isEqualTo(404);
        assertThat(get(address() + "@" + SECOND_VERSION, token).statusCode()).isEqualTo(404);
    }

    @Test
    void aPinnedVersionOfASoftDeletedSkillIsNotFound() {
        // ADR 0012's 后果 section: the version rows survive a soft delete, so a resolver that only
        // asked "does this version exist" would keep serving deleted content. Both conditions are
        // checked, and the skill's being live is the first of them.
        String id = publish(first).get("id").asText();
        assertThat(get(address() + "@" + FIRST_VERSION, token).statusCode()).isEqualTo(200);

        assertThat(send(request(address(), token).DELETE().build()).statusCode()).isEqualTo(204);

        assertThat(get(address() + "@" + FIRST_VERSION, token).statusCode())
                .as("the version row is still in the database; the skill is not")
                .isEqualTo(404);
        assertThat(count("SELECT count(*) FROM skill_version WHERE skill_id = :id", Map.of("id", id)))
                .as("and this is why: a soft delete leaves every version in place")
                .isEqualTo(1);
    }

    @Test
    void everyAddressThatResolvesToNothingGivesTheSameAnswer() {
        publish(first);

        HttpResponse<String> noSuchVersion = get(address() + "@9.9.9", token);
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

    /**
     * {@code @3} was a legal address until ADR 0033, and it is not one now.
     *
     * <p>The old form is not merely rejected — it is indistinguishable from a typo, because the
     * grammar has no integer branch at all. That is the single-answer rule working as intended: an
     * address that no longer resolves says nothing about why.
     */
    @Test
    void theOldIntegerAddressNoLongerResolves() {
        publish(first);

        assertThat(get(address() + "@1", token).statusCode())
                .as("`@1` is not a version name, so it is the address's 404 like any other nothing")
                .isEqualTo(404);
        assertThat(get(address() + "@3", token).statusCode()).isEqualTo(404);
    }

    /**
     * A version whose author declared no {@code version} has no name, and the digest is what
     * addresses it.
     *
     * <p>Declaring none is ordinary, not a defect (ADR 0033) — the host's own plugin loader falls back
     * to the commit SHA the same way — so the wire has to carry the two halves differently: a null
     * name and a real digest. The digest is the identity, and it is the one that is always there.
     */
    @Test
    void aVersionWithNoNameIsAddressableOnlyByItsDigest() {
        JsonNode submitted = publish(zip("pinned", null, "无名版", "notes"));

        assertThat(submitted.get("version").get("name").isNull())
                .as("no name on the wire, because the author declared none")
                .isTrue();
        String digest = submitted.get("version").get("digest").asText();

        JsonNode detail = JSON.readTree(get(address() + "@" + digest, token).body());
        assertThat(detail.get("version").get("name").isNull())
                .as("and the read reports the same nameless version")
                .isTrue();
        assertThat(detail.get("version").get("digest").asText()).isEqualTo(digest);
        assertThat(textOf(getBytes(bodyUriOf(detail), token))).contains("无名版");

        assertThat(get(address() + "@" + FIRST_VERSION, token).statusCode())
                .as("a name it does not have is a version that does not exist, so it is the 404")
                .isEqualTo(404);
    }

    /**
     * Two nameless versions of one skill coexist.
     *
     * <p>{@code UNIQUE (skill_id, version)} is what makes a name mean one thing for ever, and it would
     * reject the second nameless version if SQL treated two NULLs as equal. PostgreSQL treats them as
     * distinct, which is exactly why an author who declares no version can keep submitting — and why
     * this pins real behaviour rather than a restatement of the schema.
     */
    @Test
    void twoNamelessVersionsCanCoexist() {
        JsonNode a = publish(zip("pinned", null, "第一版", "notes one"));
        JsonNode b = publish(zip("pinned", null, "第二版", "notes two"));

        String digestA = a.get("version").get("digest").asText();
        String digestB = b.get("version").get("digest").asText();

        assertThat(digestA).isNotEqualTo(digestB);
        assertThat(count("SELECT count(*) FROM skill_version")).isEqualTo(2);
        assertThat(textOf(getBytes(address() + "@" + digestA + "/body", token)))
                .as("each nameless version is addressed by its own digest, and serves its own bytes")
                .contains("第一版");
        assertThat(textOf(getBytes(address() + "@" + digestB + "/body", token))).contains("第二版");
    }

    @Test
    void aWriteAddressDoesNotAcceptAVersionSuffix() {
        // §4.3: a write acts on the skill, and a version is produced or moved by the operation rather
        // than named by the caller. So the suffix is not stripped — it is part of the name, and no
        // skill can hold the character (M5 reserves it), so the address names nothing.
        publish(first);
        publish(second);

        assertThat(send(request(address() + "@" + FIRST_VERSION, token).DELETE().build())
                .statusCode())
                .isEqualTo(404);
        assertThat(get(address(), token).statusCode())
                .as("and nothing was deleted on the way to that answer")
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------
    // The version list
    // ---------------------------------------------------------------------------------------

    @Test
    void theVersionListIsThePublishedOnesNewestSubmissionFirst() {
        publish(first);
        publish(second);

        JsonNode versions = versionsOf(address());

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).propertyNames())
                .as("the contract, field by field — there is no `state` because on this plane every "
                        + "row could only say `published`")
                .containsExactlyInAnyOrder("name", "digest", "published_at", "is_current");
        assertThat(versions.get(0).get("name").asText()).isEqualTo(SECOND_VERSION);
        assertThat(versions.get(1).get("name").asText()).isEqualTo(FIRST_VERSION);
        assertThat(versions.get(0).get("digest").asText()).matches("sha256:[0-9a-f]{64}");
        assertThat(versions.get(0).get("published_at").asText()).isNotBlank();
        assertThat(versions.get(0).get("is_current").asBoolean()).isTrue();
        assertThat(versions.get(1).get("is_current").asBoolean())
                .as("a superseded version is what this endpoint is FOR: still published, still "
                        + "addressable, and no longer discoverable from the pointer")
                .isFalse();
    }

    @Test
    void aDraftIsNotInTheVersionList() {
        publish(first);
        assertThat(submit(second).statusCode()).isEqualTo(201);

        JsonNode versions = versionsOf(address());

        assertThat(versions)
                .as("a draft is not addressable on this plane, so it is not a version a client "
                        + "could pin even if it were shown")
                .hasSize(1);
        assertThat(versions.get(0).get("name").asText()).isEqualTo(FIRST_VERSION);
        assertThat(count("SELECT count(*) FROM skill_version"))
                .as("and it is really there — absent from the list rather than absent from the table")
                .isEqualTo(2);
    }

    @Test
    void aSkillWithNothingPublishedHasNoVersionList() {
        assertThat(submit(first).statusCode()).isEqualTo(201);

        assertThat(get(address() + "/versions", token).statusCode())
                .as("a draft-only skill is answered the way the rest of this plane answers it — the "
                        + "ordinary 404 — rather than as a skill that exists with an empty list, "
                        + "which would make this the one endpoint with a second meaning")
                .isEqualTo(404);
    }

    @Test
    void anAddressNamingNothingHasNoVersionList() {
        publish(first);

        assertThat(get(address() + "-typo/versions", token).statusCode())
                .as("no such skill in a namespace that exists")
                .isEqualTo(404);
        assertThat(get("/api/v1/skills/nobody-owns-this/pinned/versions", token).statusCode())
                .as("no such namespace")
                .isEqualTo(404);
    }

    @Test
    void theVersionListRefusesAVersionHeader() {
        publish(first);

        HttpResponse<String> refused = send(request(address() + "/versions", token)
                .header("X-Skill-Version", FIRST_VERSION).GET().build());

        assertThat(refused.statusCode())
                .as("no version is being selected here, so a pin has nothing to select — and "
                        + "dropping it quietly is the shape of 'I set it and it did nothing'")
                .isEqualTo(400);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private JsonNode versionsOf(String skillAddress) {
        HttpResponse<String> listed = get(skillAddress + "/versions", token);
        assertThat(listed.statusCode()).as("listing failed: %s", listed.body()).isEqualTo(200);
        return JSON.readTree(listed.body()).get("versions");
    }


    private String address() {
        return "/api/v1/skills/" + username + "/pinned";
    }

    /**
     * Both halves of ADR 0031: the content goes up over the API as a draft, and a person makes it
     * live in the browser. Returns the submission's response body, which carries the version name and
     * digest these tests are about.
     */
    private JsonNode publish(byte[] zip) {
        HttpResponse<String> submitted = submit(zip);
        assertThat(submitted.statusCode()).as("submit failed: %s", submitted.body()).isEqualTo(201);
        JsonNode body = JSON.readTree(submitted.body());
        makeLive(suffixOf(body));
        return body;
    }

    private HttpResponse<String> submit(byte[] zip) {
        Multipart multipart = Multipart.create().file("file", "pinned.zip", zip);
        return send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }

    /** How a submission's version is addressed: its name when it has one, else its digest. */
    private static String suffixOf(JsonNode submitBody) {
        JsonNode version = submitBody.get("version");
        return version.get("name").isNull() ? version.get("digest").asText()
                : version.get("name").asText();
    }

    private void makeLive(String suffix) {
        HttpResponse<String> published = webPost("/web/skills/" + username + "/pinned/publish",
                json(Map.of("version", suffix)));
        assertThat(published.statusCode()).as("publish failed: %s", published.body()).isEqualTo(200);
    }

    /**
     * One skill, wrapped in a directory named after it, with content that differs per version.
     *
     * @param version the semver to declare in the frontmatter, or null to declare none — the two
     *                address the version differently, which is the point of having both here
     */
    private static byte[] zip(String name, String version, String marker, String notes) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(name + "/SKILL.md",
                "---\nname: " + name + "\ndescription: a skill for the pinning tests\n"
                        + (version == null ? "" : "version: \"" + version + "\"\n") + "---\n"
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
