package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import com.skillmasterai.usecase.SoftDeleteSkillUseCase;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The submit path end to end: M5 validates, M6 stores, M7 records, M10 trails, and the whole thing
 * commits or rolls back as one transaction.
 *
 * <p><strong>Everything here stops at a draft, and that is the point of ADR 0031.</strong> This is
 * the write path a CLI and a CI job reach, so nothing it does may be visible on the consumption
 * plane; publishing is a separate act on a separate plane, and its tests live with that plane.
 * Several of the assertions below are therefore about absence — nothing listed, nothing readable —
 * which is a harder thing to keep true than a 200, and the reason to assert it here.
 *
 * <p>The rejections M5 owns — 513 files, 16 MiB, traversal, symlinks — are proven in
 * {@code SkillUploadValidatorTest}, where they need no server. Two of them are repeated here for a
 * different reason: what is under test over HTTP is the exception-to-envelope mapping and the
 * multipart ceiling, neither of which exists below the API. Repeating all of them would re-test M5
 * at a higher cost.
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillSubmitIT extends AbstractAccountIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The version every fixture declares unless a test needs a second one. */
    private static final String DEFAULT_VERSION = "1.0.0";

    /**
     * Where deleting lives now that the API plane has no route for it (ADR 0046).
     *
     * <p>The cases below that need a soft-deleted skill call this instead of a request. It is the
     * same call the removed route made — one layer down, and the layer the console entry will make
     * its own — so what they are about is unchanged; see {@link #aCredentialCannotDeleteASkill} for
     * the decision itself.
     */
    @Autowired
    private SoftDeleteSkillUseCase softDeleteSkill;

    @Test
    void submitsAZipAndReturnsTheCreatedVersion() {
        byte[] zip = skill("pdf-tools", Map.of());

        HttpResponse<String> response = submit(zip);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(response.body());

        // Key names are asserted, not just values: §4.2's wire format is snake_case, and it is set
        // globally on the ObjectMapper rather than annotated field by field — so a field whose name
        // silently changed shape would otherwise go unnoticed.
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "id", "name", "namespace", "created", "skill_created", "version");
        assertThat(body.get("version").propertyNames()).containsExactlyInAnyOrder(
                "name", "digest", "file_count", "total_bytes", "submitted_at", "state");
        // Answered rather than left for the client to derive: a replay names whichever row already
        // holds that digest, and the unique constraint is on the content rather than on the state, so
        // "which state is this version in" is not a question a client can answer on its own.
        assertThat(body.get("version").get("state").asText()).isEqualTo("draft");

        assertThat(Ulid.isValid(body.get("id").asText())).isTrue();
        assertThat(body.get("name").asText()).isEqualTo("pdf-tools");
        assertThat(body.get("namespace").asText()).isEqualTo("demo");
        assertThat(body.get("created").asBoolean()).isTrue();
        assertThat(body.get("skill_created").asBoolean())
                .as("the first submission is the one that creates the skill")
                .isTrue();
        assertThat(body.get("version").get("name").asText())
                .as("the name the author declared, carried through verbatim (ADR 0033)")
                .isEqualTo(DEFAULT_VERSION);

        assertThat(body.get("version").get("digest").asText())
                // The prefix plus 64 hex characters: the API presents the prefix, storage keeps the
                // bare hex that ADR 0005's formula produces.
                .hasSize("sha256:".length() + 64)
                .matches("sha256:[0-9a-f]{64}");
        assertThat(body.get("version").get("file_count").asInt()).isEqualTo(1);
        assertThat(body.get("version").get("total_bytes").asLong())
                .isEqualTo(skillMd("pdf-tools", DEFAULT_VERSION).getBytes(StandardCharsets.UTF_8).length);
        assertThat(body.get("version").get("submitted_at").asText())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    }

    /**
     * ADR 0031's central claim about this path: a submission changes nothing a consumer can see.
     *
     * <p>The skill row now exists — it has to, or the next submission would not know it is adding a
     * version to the same skill — but it is not addressable, not listed and not searchable, because
     * nothing has been published from it. The pointer is what all three read through, and it is
     * null.
     */
    @Test
    void aSubmissionIsInvisibleToTheConsumptionPlane() {
        assertThat(submit(skill("pdf-tools", Map.of())).statusCode()).isEqualTo(201);

        JsonNode listing = JSON.readTree(get("/api/v1/skills", token()).body());
        assertThat(listing.get("skills")).isEmpty();

        assertThat(get("/api/v1/skills/demo/pdf-tools", token()).statusCode())
                .as("the address resolves to nothing while the only version is a draft")
                .isEqualTo(404);
        assertThat(getBytes("/api/v1/skills/demo/pdf-tools/body", token()).statusCode())
                .as("and so does the body")
                .isEqualTo(404);

        assertThat(count("SELECT count(*) FROM skill WHERE current_version_id IS NULL"))
                .as("nothing was published, so no skill points at a version")
                .isEqualTo(1);
    }

    @Test
    void submittingLeavesAnAuditTrail() {
        // §3.5: content is taken away and used in client environments, so attribution afterwards is
        // the floor. The row is written inside the submit transaction, which is what makes it
        // evidence rather than a note.
        String id = submitAndReadId(skill("pdf-tools", Map.of()));

        assertThat(count("""
                SELECT count(*) FROM audit_event
                WHERE action = 'submit' AND target_type = 'skill' AND target_id = :id
                  AND actor_user_id = :actor
                """, Map.of("id", id, "actor", SUBJECT_USER_ID)))
                .isEqualTo(1);
    }

    @Test
    void resubmittingIdenticalContentIsIdempotent() {
        byte[] zip = skill("pdf-tools", Map.of());

        HttpResponse<String> first = submit(zip);
        HttpResponse<String> second = submit(zip);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode())
                .as("a replay is a success with a different status, not an error")
                .isEqualTo(200);

        JsonNode before = JSON.readTree(first.body());
        JsonNode after = JSON.readTree(second.body());

        assertThat(after.get("created").asBoolean()).isFalse();
        assertThat(after.get("skill_created").asBoolean())
                .as("the skill existed already, so this call created no skill either")
                .isFalse();
        assertThat(after.get("id").asText())
                .as("the same skill, not a second one that happens to share the name")
                .isEqualTo(before.get("id").asText());
        assertThat(after.get("version").get("digest").asText())
                .isEqualTo(before.get("version").get("digest").asText());
        assertThat(after.get("version").get("name").asText())
                .as("identical bytes under the same version name is still ADR 0005's idempotence — "
                        + "one version, answered 200, not version_already_exists")
                .isEqualTo(before.get("version").get("name").asText());
        assertThat(count("SELECT count(*) FROM skill_version")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM skill")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM version_file")).isEqualTo(1);
    }

    @Test
    void namesCountDistinctContentAndAReplayConsumesNone() {
        // ADR 0033: the name is the author's declaration, so what a client receives is what the
        // SKILL.md said. A replay of identical content must still be one version — otherwise the same
        // digest would hold two rows and ADR 0005's idempotence would be dead. The internal number
        // still allocates a submission position per distinct content, which is the last assertion.
        byte[] first = skill("pdf-tools", "1.0.0", Map.of("references/a.md", "a"));
        byte[] second = skill("pdf-tools", "2.0.0", Map.of("references/b.md", "b"));

        assertThat(versionNameOf(submit(first))).isEqualTo("1.0.0");
        assertThat(versionNameOf(submit(first)))
                .as("a replay is not a new version, so it reports the one that already exists")
                .isEqualTo("1.0.0");
        assertThat(versionNameOf(submit(second))).isEqualTo("2.0.0");
        assertThat(versionNameOf(submit(first)))
                .as("and the replay still resolves to its own original name")
                .isEqualTo("1.0.0");

        assertThat(jdbc.sql("SELECT number FROM skill_version ORDER BY number")
                .query(Integer.class).list())
                .as("no gap and no duplicate: the internal numbers are exactly the contents submitted")
                .containsExactly(1, 2);
    }

    @Test
    void concurrentSubmissionsOfOneSkillEachGetTheirOwnName() throws Exception {
        // The number is MAX(number) + 1 computed inside the insert, and that is serialised only
        // because submitting takes the skill row's lock first (findOrCreate's ON CONFLICT DO
        // UPDATE). The names come from the declarations, one per publisher, so every one of them is a
        // real insert and each keeps its own name — while the internal numbers stay a clean 1..N.
        // This is the test that fails — as a UNIQUE (skill_id, number) violation surfacing as a 500,
        // or as a duplicate name — if that convention is ever broken.
        int publishers = 8;
        List<byte[]> zips = IntStream.range(0, publishers)
                .mapToObj(i -> skill("pdf-tools", (i + 1) + ".0.0",
                        Map.of("references/f" + i + ".md", "content " + i)))
                .toList();

        // Released together, so the requests genuinely overlap rather than queueing in the client.
        // A correct implementation cannot fail this regardless of how the threads interleave.
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(publishers)) {
            List<Future<String>> futures = zips.stream()
                    .map(zip -> pool.submit(() -> {
                        start.await();
                        return versionNameOf(submit(zip));
                    }))
                    .toList();
            start.countDown();

            List<String> names = new ArrayList<>();
            for (Future<String> future : futures) {
                names.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(names).doesNotHaveDuplicates();
            assertThat(names).containsExactlyInAnyOrderElementsOf(
                    IntStream.rangeClosed(1, publishers).mapToObj(i -> i + ".0.0").toList());
        }
        assertThat(count("SELECT count(*) FROM skill_version")).isEqualTo(publishers);
        assertThat(jdbc.sql("SELECT number FROM skill_version ORDER BY number")
                .query(Integer.class).list())
                .as("the internal ordering column is still allocated once per distinct content")
                .containsExactlyElementsOf(
                        IntStream.rangeClosed(1, publishers).boxed().toList());
    }

    @Test
    void concurrentSubmissionsOfDifferentSkillsDoNotDeadlockEachOther() throws Exception {
        // Submissions of *different* skills contend on nothing the row locks cover, so they reach
        // the reclamation sweep together — and a sweep lock taken there, after each has already
        // inserted into version_file and holds ROW EXCLUSIVE on it, deadlocks the pair: each waits
        // on the other's insert lock. It is taken before any write for exactly that reason, and
        // this is the test that fails — a 500 out of PostgreSQL, after deadlock_timeout — if it
        // ever moves back. The same-skill test above cannot see this one, because findOrCreate
        // serialises those submitters before either reaches version_file.
        int publishers = 8;
        List<byte[]> zips = IntStream.range(0, publishers)
                .mapToObj(i -> skill("pdf-tools-" + i, Map.of("references/f.md", "content " + i)))
                .toList();

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(publishers)) {
            List<Future<Integer>> statuses = zips.stream()
                    .map(zip -> pool.submit(() -> {
                        start.await();
                        return submit(zip).statusCode();
                    }))
                    .toList();
            start.countDown();

            for (Future<Integer> future : statuses) {
                assertThat(future.get(30, TimeUnit.SECONDS))
                        .as("every submission of a distinct skill succeeds")
                        .isEqualTo(201);
            }
        }
    }

    @Test
    void concurrentSubmissionsSharingFilesDoNotDeadlockOnTheSharedBlobs() throws Exception {
        // Two archives that share files but list them in opposite orders. Storing a file touches a
        // row keyed by its digest, and ON CONFLICT DO NOTHING waits on a row another transaction
        // has inserted but not committed — so taking those rows in different orders is an ABBA
        // deadlock. The bytes are stored in a content-derived order for exactly this reason, and
        // this is the test that fails (a 500 after deadlock_timeout, rolling one submission back)
        // if that ordering goes away. The files are large enough that both submitters are still
        // inside the store loop when the other asks for a row it holds.
        Map<String, String> forwards = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            forwards.put("references/shared-" + i + ".md", "shared " + i + " " + "x".repeat(200_000));
        }
        Map<String, String> backwards = new LinkedHashMap<>();
        List<String> paths = new ArrayList<>(forwards.keySet());
        for (int i = paths.size() - 1; i >= 0; i--) {
            backwards.put(paths.get(i), forwards.get(paths.get(i)));
        }

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> statuses = List.of(
                    pool.submit(() -> {
                        start.await();
                        return submit(skill("alpha", forwards)).statusCode();
                    }),
                    pool.submit(() -> {
                        start.await();
                        return submit(skill("beta", backwards)).statusCode();
                    }));
            start.countDown();

            for (Future<Integer> future : statuses) {
                assertThat(future.get(60, TimeUnit.SECONDS))
                        .as("both submitters finish; a deadlock arrives here as a 500")
                        .isEqualTo(201);
            }
        }
    }

    @Test
    void twoSkillsSharingAFileStoreTheBytesOnce() {
        // Contract libraries share boilerplate, so the same bytes arriving under two skills is the
        // normal case rather than a corner. Deduplication is by content hash, which is also why a
        // blob cannot be deleted while any version still references it.
        String shared = "a shared glossary";

        submit(skill("first", Map.of("references/glossary.md", shared)));
        submit(skill("second", Map.of("references/glossary.md", shared)));

        String sha = sha256Hex(shared.getBytes(StandardCharsets.UTF_8));
        assertThat(count("SELECT count(*) FROM version_file WHERE blob_sha256 = :sha",
                Map.of("sha", sha)))
                .as("both versions point at the same content")
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM blob_content WHERE sha256 = :sha", Map.of("sha", sha)))
                .as("the bytes are stored once")
                .isEqualTo(1);
    }

    @Test
    void aRejectedUploadComesBackAsAnEnvelopeWithAFieldLevelReason() {
        // What is under test is the mapping, not the rejection: M5 already refuses symlinks in a
        // unit test. What exists only at this level is the shape a client has to parse.
        HttpResponse<String> response = submit(Zips.withSymlink("SKILL.md", "/etc/passwd"));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = JSON.readTree(response.body()).get("error");

        assertThat(error.get("code").asText()).isEqualTo("invalid_upload");
        assertThat(error.get("message").asText()).contains("symbolic link");
        assertThat(error.get("details").get(0).get("field").asText()).isEqualTo("SKILL.md");
        assertThat(error.get("details").get(0).get("issue").asText()).isEqualTo("symlink_not_allowed");
    }

    @Test
    void aRequestWithoutAFilePartIsRejectedOnThePartItIsMissing() {
        HttpResponse<String> response =
                post(Multipart.create().file("not-the-file", "x.zip", new byte[0]));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = JSON.readTree(response.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("invalid_upload");
        assertThat(error.get("details").get(0).get("field").asText()).isEqualTo("file");
        assertThat(error.get("details").get(0).get("issue").asText()).isEqualTo("missing");
    }

    @Test
    void aBodyBeyondTheMultipartCeilingIsRefusedBeforeAnythingReadsIt() {
        // The ceiling is not the skill limit. It exists so that an absurd body is dropped by the
        // container instead of being buffered and then judged — the validator's answer is the
        // useful one for a legal-but-oversized skill, and this is the backstop for everything else.
        byte[] oversized = new byte[33 * 1024 * 1024];
        new Random(1).nextBytes(oversized);

        HttpResponse<String> response = post(Multipart.create().file("file", "huge.zip", oversized));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(JSON.readTree(response.body()).get("error").get("code").asText())
                .isEqualTo("invalid_upload");
    }

    @Test
    void aBlobIsReclaimedOnceNoVersionReferencesIt() {
        // The only way to orphan a blob in P0 is to remove a version, and no P0 operation does
        // that (§3.3 point 5: soft delete keeps every version, and discard only marks one). So the
        // row is removed directly — what is under test is the sweep, not how a version comes to
        // disappear.
        submit(skill("doomed", Map.of("references/only-here.md", "unreferenced content")));
        assertThat(count("SELECT count(*) FROM blob_content")).isEqualTo(2);

        jdbc.sql("DELETE FROM skill_version WHERE skill_id ="
                + " (SELECT id FROM skill WHERE name = 'doomed')").update();

        // Any submission runs the sweep; this one is only the trigger.
        submit(skill("survivor", Map.of()));

        assertThat(count("SELECT count(*) FROM blob_content"))
                .as("the orphaned bytes are gone, and the trigger's own are not")
                .isEqualTo(1);
    }

    @Test
    void deletingASkillIsASoftDeleteAndASecondDeleteFindsNothing() {
        String id = submitAndReadId(skill("pdf-tools", Map.of()));

        assertThat(delete("demo", "pdf-tools"))
                .as("the delete reports which skill it removed, which is the identity rather than "
                        + "the address it was reached through")
                .contains(id);

        assertThat(delete("demo", "pdf-tools"))
                .as("already deleted is indistinguishable from never existed, on purpose")
                .isEmpty();
        assertThat(delete("demo", "never-existed"))
                .as("and this is what makes them indistinguishable: one nothing for both, so the "
                        + "second delete says nothing the first did not")
                .isEmpty();

        assertThat(count("SELECT count(*) FROM skill WHERE id = :id AND deleted_at IS NOT NULL",
                Map.of("id", id)))
                .as("§3.3 point 5: the row and its versions survive, so the delete can be undone")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM skill_version WHERE skill_id = :id", Map.of("id", id)))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM blob_content"))
                .as("a soft delete leaves every version referenced, so nothing is garbage yet")
                .isEqualTo(1);
    }

    /**
     * ADR 0046: no credential can take a skill away from everybody, so this plane has no delete.
     *
     * <p><strong>The case that stood here was about the answer, and the answer is gone.</strong> It
     * deleted somebody else's skill and asserted 404 rather than 403 — but there is no longer a
     * request to make that assertion about. The ownership rule behind it is unchanged and is pinned
     * where it is enforced, at the layer the route used to call
     * ({@code SkillVersionServiceIT.aSkillCannotBeDeletedThroughANamespaceThatDoesNotOwnIt}), which
     * is also the only place it can be pinned: as that class says, through HTTP the namespace check
     * answers first, so a bug in the predicate would be invisible. What is left to pin here is the
     * decision itself.
     */
    @Test
    void aCredentialCannotDeleteASkill() {
        String id = submitAndReadId(skill("pdf-tools", Map.of()));

        HttpResponse<String> refused =
                send(request("/api/v1/skills/demo/pdf-tools", token()).DELETE().build());

        assertThat(refused.statusCode())
                .as("405 and not 403: deleting is not a permission this credential was refused, it "
                        + "is a route the plane does not have — which is the difference between "
                        + "\"your key is too narrow\" and \"a credential cannot do this at all\"")
                .isEqualTo(405);
        assertThat(refused.headers().firstValue(HttpHeaders.ALLOW))
                .as("and the 405 still says which methods would work (RFC 9110)")
                .hasValueSatisfying(allow -> {
                    List<String> methods = List.of(allow.split(",")).stream()
                            .map(String::trim)
                            .map(String::toUpperCase)
                            .toList();
                    assertThat(methods)
                            .as("the path serves GET — HEAD comes with it, and no delete route is "
                                    + "back")
                            .contains("GET")
                            .doesNotContain("POST", "DELETE");
                });

        assertThat(count("SELECT count(*) FROM skill WHERE id = :id AND deleted_at IS NULL",
                Map.of("id", id)))
                .as("and the skill is untouched")
                .isEqualTo(1);
    }

    @Test
    void submittingOverADeletedNameIsRefusedRatherThanResurrectingIt() {
        byte[] zip = skill("pdf-tools", Map.of());
        String id = submitAndReadId(zip);
        assertThat(delete("demo", "pdf-tools"))
                .as("the arrangement: 'pdf-tools' is soft-deleted rather than gone")
                .isPresent();

        HttpResponse<String> response = submit(zip);

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = JSON.readTree(response.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("invalid_request");
        assertThat(error.get("message").asText()).contains("deleted");
        assertThat(count("SELECT count(*) FROM skill WHERE id = :id AND deleted_at IS NULL",
                Map.of("id", id)))
                .as("§4.3 gives restoring its own endpoint; a submit must not be a back door to it")
                .isZero();
    }

    /**
     * {@code skill_created} answers the one question {@code created} cannot.
     *
     * <p>{@code created} means "this call wrote a version", which is true both for a brand-new skill
     * and for a new version of one that has existed for months. {@code skill_created} means "this call
     * created the skill". A CLI needs the second to say 已创建 rather than 已提交, and it is the only
     * field that can now: {@code number == 1} used to carry the distinction and the number has left
     * the wire (ADR 0033).
     */
    @Test
    void skillCreatedSeparatesANewSkillFromANewVersion() {
        JsonNode creation = JSON.readTree(submit(skill("pdf-tools", Map.of())).body());
        assertThat(creation.get("created").asBoolean()).isTrue();
        assertThat(creation.get("skill_created").asBoolean())
                .as("the first submission is the one that brings the skill into being")
                .isTrue();

        JsonNode laterVersion =
                JSON.readTree(submit(skill("pdf-tools", "2.0.0", Map.of())).body());
        assertThat(laterVersion.get("created").asBoolean())
                .as("this call wrote a new version, so created is true as well")
                .isTrue();
        assertThat(laterVersion.get("skill_created").asBoolean())
                .as("but the skill existed already — and only this field can say so")
                .isFalse();
    }

    /**
     * A version name is immutable: the same name with different content is refused.
     *
     * <p>ADR 0033 §6. The refusal has its own code rather than {@code invalid_request}, because the
     * fix is specific — increment the {@code version} in {@code SKILL.md} — and a submission that
     * fails this way would otherwise look exactly like one that worked.
     */
    @Test
    void aTakenVersionNameWithDifferentContentIsRefused() {
        submit(skill("pdf-tools", "1.0.0", Map.of("references/a.md", "a")));

        HttpResponse<String> response =
                submit(skill("pdf-tools", "1.0.0", Map.of("references/b.md", "b")));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("error").get("code").asText())
                .as("a code of its own, so a client can tell the author which mistake they made")
                .isEqualTo("version_already_exists");
        assertThat(count("SELECT count(*) FROM skill_version"))
                .as("the refused submission wrote no version")
                .isEqualTo(1);
    }

    /**
     * The same name with identical content is still ADR 0005's idempotent 200, not a 400.
     *
     * <p>The two rules meet here and do not conflict: {@code version_already_exists} is about a name
     * being reused for <em>different</em> content, while identical content is the same version by its
     * digest. This pins the boundary between them.
     */
    @Test
    void theSameVersionNameWithIdenticalContentIsIdempotent() {
        byte[] zip = skill("pdf-tools", "1.0.0", Map.of());

        assertThat(submit(zip).statusCode()).isEqualTo(201);
        HttpResponse<String> replay = submit(zip);

        assertThat(replay.statusCode())
                .as("identical content under a taken name is one version, answered 200")
                .isEqualTo(200);
        assertThat(JSON.readTree(replay.body()).get("created").asBoolean()).isFalse();
        assertThat(count("SELECT count(*) FROM skill_version")).isEqualTo(1);
    }

    /**
     * A version that is not a semver is refused at upload, on the field that is wrong.
     *
     * <p>M5 owns the rule and proves it in {@code SkillUploadValidatorTest}; what exists only here is
     * the exception-to-envelope mapping, and the version is a field an author fixes in their
     * {@code SKILL.md} — so the response has to name it.
     */
    @Test
    void aNonSemverVersionIsRefusedOnTheVersionField() {
        HttpResponse<String> response =
                submit(skill("pdf-tools", "1.0", Map.of()));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = JSON.readTree(response.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("invalid_upload");
        assertThat(error.get("details").get(0).get("field").asText()).isEqualTo("version");
        assertThat(error.get("details").get(0).get("issue").asText()).isEqualTo("version_not_semver");
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private HttpResponse<String> submit(byte[] zip) {
        return post(Multipart.create().file("file", "skill.zip", zip));
    }

    private HttpResponse<String> post(Multipart multipart) {
        return send(request("/api/v1/skills", token())
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }

    /**
     * Soft-deletes a skill, as the removed API route did.
     *
     * <p>Named after the request that used to be here so the tests read the same way, but it is a
     * call rather than a request (ADR 0046): the address's namespace and the ownership check still
     * happen, and "which skill was removed" still comes back as a value rather than as a status —
     * the difference is that the empty one is returned instead of being rendered as §4.1's 404.
     *
     * <p>The subject is a session's, which is what deleting is now: {@code ofSession} carries the
     * user id alone and says why in one place. The test's credential is an API key, and the point of
     * the ADR is that a key is not what does this.
     */
    private Optional<String> delete(String namespaceSlug, String name) {
        return softDeleteSkill.softDelete(namespaceSlug, name,
                AuthenticatedSubject.ofSession(SUBJECT_USER_ID));
    }

    /** The version name a submission reported — 201 for content new to the skill, 200 for a replay. */
    private static String versionNameOf(HttpResponse<String> response) {
        assertThat(response.statusCode())
                .as("submit failed: %s", response.body())
                .isIn(200, 201);
        return JSON.readTree(response.body()).get("version").get("name").asText();
    }

    private String submitAndReadId(byte[] zip) {
        HttpResponse<String> response = submit(zip);
        assertThat(response.statusCode()).as("submit failed: %s", response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("id").asText();
    }

    /** A zip holding one skill, wrapped in a directory named after it — what `zip -r x.zip name/` makes. */
    private static byte[] skill(String name, Map<String, String> extraFiles) {
        return skill(name, DEFAULT_VERSION, extraFiles);
    }

    /** The same, with the version the frontmatter declares chosen by the test. */
    private static byte[] skill(String name, String version, Map<String, String> extraFiles) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(name + "/SKILL.md", skillMd(name, version));
        extraFiles.forEach((relpath, content) -> files.put(name + "/" + relpath, content));
        return Zips.ofText(files);
    }

    private static String skillMd(String name, String version) {
        return "---\nname: " + name + "\ndescription: A skill used by the submit tests\n"
                + "version: \"" + version + "\"\n---\n# " + name + "\n";
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE", e);
        }
    }
}
