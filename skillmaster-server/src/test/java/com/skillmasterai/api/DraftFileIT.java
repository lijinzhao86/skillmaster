package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.ShortCode;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.version.internal.VersionRepository;
import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * Draft file I/O on the author plane (v7): reading, writing, moving, copying, counting and
 * searching one version — and the rungs that are refused each of them.
 *
 * <p>The property the class exists for is that a write changes the draft and nothing else: the
 * skill row, the live version and the caller's address all stay where they were, while the digest
 * moves and {@code version_file} is what changed.
 */
@Sql("/sql/truncate-business-tables.sql")
class DraftFileIT extends AbstractAccountIT {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private String username;
    private String token;

    @BeforeEach
    void signUp() {
        username = randomUsername();
        token = tokenFor(registerAndSignIn(username, randomPhone()));
    }

    @Test
    void aWriteIsReadBackAndMovesOnlyTheDraft() {
        String code = submitDraft(Map.of("references/a.md", "one\n"));

        String digestBefore = send(request(versionPath(code, ""), token).GET().build()).body();
        String originalDigest = JSON.readTree(digestBefore).get("version").get("digest").asText();
        HttpResponse<String> before = send(request(versionPath(code, "/files"), token).GET().build());
        assertThat(before.statusCode()).as(before.body()).isEqualTo(200);
        String etag = before.headers().firstValue(HttpHeaders.ETAG).orElseThrow();
        assertThat(etag).startsWith("\"sha256:");

        HttpResponse<String> written = put(code, "references/b.md", "two\n", etag);
        assertThat(written.statusCode()).as(written.body()).isEqualTo(200);
        JsonNode after = JSON.readTree(written.body()).get("version");
        assertThat(after.get("code").asText()).as("the row id never moves").isEqualTo(code);
        assertThat(after.get("pin").asText()).isEqualTo(code);
        assertThat(after.get("files").asInt()).isEqualTo(3);
        assertThat(after.get("digest").asText()).isNotEqualTo(originalDigest);

        HttpResponse<String> read = send(request(versionPath(code, "/files/references/b.md"),
                token).GET().build());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).isEqualTo("two\n");
        assertThat(read.headers().firstValue(HttpHeaders.ETAG)).isPresent().get().asString()
                .startsWith("\"sha256:");

        // The same bytes again is the same state reached twice: 200, and nothing written.
        String second = read.headers().firstValue(HttpHeaders.ETAG).orElseThrow();
        HttpResponse<String> replay = put(code, "references/b.md", "two\n", second);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(JSON.readTree(replay.body()).get("version").get("digest").asText())
                .isEqualTo(after.get("digest").asText());

        // Zero bytes is a file, not a missing body (v7 做 #10: empty stdin writes an empty file).
        String third = listEtag(code);
        HttpResponse<String> empty = put(code, "empty.txt", "", third);
        assertThat(empty.statusCode()).as(empty.body()).isEqualTo(200);
        HttpResponse<String> emptyRead = send(request(versionPath(code, "/files/empty.txt"),
                token).GET().build());
        assertThat(emptyRead.statusCode()).as(emptyRead.body()).isEqualTo(200);
        assertThat(emptyRead.body()).isEmpty();
    }

    @Test
    void aWriteWithoutTheBaselineIs428AndAStaleOneIs409() {
        String code = submitDraft(Map.of("references/a.md", "one\n"));

        HttpResponse<String> missing = send(request(versionPath(code, "/files/notes.md"), token)
                .PUT(HttpRequest.BodyPublishers.ofString("x")).build());
        assertThat(missing.statusCode()).isEqualTo(428);
        assertThat(codeOf(missing)).isEqualTo("if_match_required");

        HttpResponse<String> stale = put(code, "notes.md", "x",
                "\"sha256:" + "0".repeat(64) + "\"");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(codeOf(stale)).isEqualTo("digest_mismatch");
    }

    @Test
    void theRootSkillMdCannotBeRemovedAndAMoveOrCopyRefusesAnExistingTarget() {
        String code = submitDraft(Map.of("references/a.md", "one\n", "references/b.md", "two\n"));
        String etag = listEtag(code);

        HttpResponse<String> root = send(request(versionPath(code, "/files/SKILL.md"), token)
                .header(HttpHeaders.IF_MATCH, etag).DELETE().build());
        assertThat(root.statusCode()).isEqualTo(409);
        assertThat(codeOf(root)).isEqualTo("skill_md_cannot_be_deleted");

        HttpResponse<String> copied = send(request(versionPath(code, "/files"), token)
                .header(HttpHeaders.IF_MATCH, etag)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of(
                        "op", "cp", "from", "references/a.md", "to", "references/c.md"))))
                .build());
        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(200);
        String etagAfterCopy = listEtag(code);

        HttpResponse<String> clash = send(request(versionPath(code, "/files"), token)
                .header(HttpHeaders.IF_MATCH, etagAfterCopy)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of(
                        "op", "mv", "from", "references/c.md", "to", "references/b.md"))))
                .build());
        assertThat(clash.statusCode()).isEqualTo(409);
        assertThat(codeOf(clash)).isEqualTo("file_already_exists");

        HttpResponse<String> moved = send(request(versionPath(code, "/files"), token)
                .header(HttpHeaders.IF_MATCH, etagAfterCopy)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of(
                        "op", "mv", "from", "references/c.md", "to", "references/d.md"))))
                .build());
        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);

        HttpResponse<String> listed = send(request(versionPath(code, "/files"), token).GET().build());
        assertThat(listed.body()).contains("references/d.md").doesNotContain("references/c.md");
    }

    @Test
    void grepFindsLinesOnlyInADraftAndCountsMatchTheFiles() {
        String code = submitDraft(Map.of("references/a.md", "alpha\nneedle here\nomega\n"));

        HttpResponse<String> grep = send(request(versionPath(code,
                "/grep?pattern=needle&fixed=true"), token).GET().build());
        assertThat(grep.statusCode()).as(grep.body()).isEqualTo(200);
        JsonNode hits = JSON.readTree(grep.body()).get("hits");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("path").asText()).isEqualTo("references/a.md");
        assertThat(hits.get(0).get("line").asInt()).isEqualTo(2);
        assertThat(hits.get(0).get("text").asText()).isEqualTo("needle here");

        // A file ending in a newline has no phantom empty last line: `^$` matches nothing here.
        HttpResponse<String> blank = send(request(versionPath(code,
                "/grep?pattern=" + java.net.URLEncoder.encode("^$", StandardCharsets.UTF_8)),
                token).GET().build());
        assertThat(blank.statusCode()).as(blank.body()).isEqualTo(200);
        assertThat(JSON.readTree(blank.body()).get("hits")).isEmpty();

        // `-F -i` is the same linear scan (ADR 0066), not a per-start comparison loop.
        HttpResponse<String> folded = send(request(versionPath(code,
                "/grep?pattern=NEEDLE&fixed=true&ignore_case=true"), token).GET().build());
        assertThat(folded.statusCode()).as(folded.body()).isEqualTo(200);
        assertThat(JSON.readTree(folded.body()).get("hits")).hasSize(1);

        HttpResponse<String> count = send(request(versionPath(code, "/count?path=references/a.md"),
                token).GET().build());
        assertThat(count.statusCode()).as(count.body()).isEqualTo(200);
        JsonNode file = JSON.readTree(count.body()).get("files").get(0);
        assertThat(file.get("lines").asLong()).as("lines are the number of newlines").isEqualTo(3);
        assertThat(file.get("words").asLong()).as("words are whitespace-separated runs").isEqualTo(4);
        assertThat(file.get("bytes").asLong()).isEqualTo("alpha\nneedle here\nomega\n".length());

        // ADR 0098: the scope is every version the caller may read, so the same scan now answers on
        // the published version — by its row id and by its release number.
        publish(code, "1.0.0");
        HttpResponse<String> published = send(request(versionPath(code,
                "/grep?pattern=needle"), token).GET().build());
        assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
        assertThat(JSON.readTree(published.body()).get("hits")).hasSize(1);

        HttpResponse<String> named = send(request("/api/v1/author/skills/" + username
                + "/pdf-tools/versions/1.0.0/grep?pattern=needle", token).GET().build());
        assertThat(named.statusCode()).as(named.body()).isEqualTo(200);
        assertThat(JSON.readTree(named.body()).get("hits")).hasSize(1);
    }

    /** The match ceiling is a property of the request, and it says so rather than pretending. */
    @Test
    void grepStopsAtTheCapAndSaysTheResultIsTruncated() {
        StringBuilder many = new StringBuilder();
        for (int line = 1; line <= 1001; line++) {
            many.append("hit ").append(line).append('\n');
        }
        String code = submitDraft(Map.of("lines.txt", many.toString()));

        HttpResponse<String> all = send(request(versionPath(code,
                "/grep?pattern=hit&fixed=true"), token).GET().build());
        assertThat(all.statusCode()).as(all.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(all.body());
        assertThat(body.get("hits")).hasSize(1000);
        assertThat(body.get("truncated").asBoolean())
                .as("the answer says it is a prefix, not the whole truth")
                .isTrue();

        HttpResponse<String> five = send(request(versionPath(code,
                "/grep?pattern=hit&fixed=true&limit=5"), token).GET().build());
        assertThat(five.statusCode()).as(five.body()).isEqualTo(200);
        assertThat(JSON.readTree(five.body()).get("hits")).hasSize(5);
        assertThat(JSON.readTree(five.body()).get("truncated").asBoolean()).isTrue();
    }

    /** PRD #16 / T-SEC: a version at the 512-file ceiling lists every entry, and no bytes. */
    @Test
    void aVersionAtTheFileCeilingEnumeratesEveryEntryWithoutSendingContent() {
        Map<String, String> files = new LinkedHashMap<>();
        for (int file = 0; file < 511; file++) {
            files.put("parts/" + file + ".txt", "content " + file + "\n");
        }
        String code = submitDraft(files);

        HttpResponse<String> listed = send(request(versionPath(code, "/files"), token).GET().build());
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        JsonNode entries = JSON.readTree(listed.body()).get("files");
        assertThat(entries).as("SKILL.md plus the 511 parts").hasSize(512);
        assertThat(entries.get(0).has("relpath")).isTrue();
        assertThat(entries.get(0).has("size")).isTrue();
        assertThat(listed.body())
                .as("enumeration names entries; it does not carry their bytes")
                .doesNotContain("content 0");
    }

    /** T-WRITE-04: a malformed path never reaches the store; the rule's codes live in SkillPathsTest. */
    @Test
    void aPathThatEscapesOrBreaksTheRootsRulesIsRefusedBeforeTheRouteSeesIt() {
        String code = submitDraft(Map.of("references/a.md", "one\n"));
        String etag = listEtag(code);

        HttpResponse<String> traversal = put(code, "../escape.md", "x", etag);
        assertThat(traversal.statusCode())
                .as("`..` is refused before the route sees it; the container's 400 is the answer")
                .isEqualTo(400);

        HttpResponse<String> emptySegment = put(code, "a//b.md", "x", etag);
        assertThat(emptySegment.statusCode())
                .as("an empty segment is refused; the rule's own issue code is pinned in "
                        + "SkillPathsTest")
                .isEqualTo(400);

        HttpResponse<String> backslash = put(code, "a%5Cb.md", "x", etag);
        assertThat(backslash.statusCode()).as(backslash.body()).isEqualTo(400);

        // Nothing was written by any refusal.
        HttpResponse<String> listed = send(request(versionPath(code, "/files"), token).GET().build());
        assertThat(JSON.readTree(listed.body()).get("files")).hasSize(2);
    }

    /** PRD #16: a version is bounded by 512 files and 16 MiB, SKILL.md included. */
    @Test
    void aVersionBeyondTheWholeSkillCeilingsIsRefusedBeforeItIsStored() {
        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int file = 0; file < 512; file++) {
            tooMany.put("parts/" + file + ".txt", "x");
        }
        HttpResponse<String> many = submitRaw(tooMany, "too many files");
        assertThat(many.statusCode()).as(many.body()).isEqualTo(400);
        assertThat(codeOf(many)).isEqualTo("invalid_upload");
        assertThat(issue(many)).isEqualTo("too_many_files");
        assertThat(count("SELECT count(*) FROM skill")).as("a refused upload stores nothing").isZero();
        assertThat(count("SELECT count(*) FROM skill_version")).isZero();
    }

    /** Two writers holding the same baseline: one wins, the other is told to re-read. */
    @Test
    void twoWritesWithTheSameBaselineLeaveOneWinnerAndOneConflict() throws Exception {
        String code = submitDraft(Map.of("references/a.md", "one\n"));
        String baseline = listEtag(code);

        var first = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> put(code, "race/first.md", "first\n", baseline));
        var second = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> put(code, "race/second.md", "second\n", baseline));
        List<Integer> statuses = List.of(first.get().statusCode(), second.get().statusCode())
                .stream().sorted().toList();

        assertThat(statuses).as("one write commits, the other sees a moved baseline")
                .containsExactly(200, 409);
        assertThat(JSON.readTree(first.get().statusCode() == 409
                ? first.get().body() : second.get().body())
                .get("error").get("code").asText()).isEqualTo("digest_mismatch");
    }

    /** The whole-version byte ceiling, walked by two writes that are each under it. */
    @Test
    void aDraftBeyondTheWholeVersionByteCeilingIsRefused() {
        String code = submitDraft(Map.of("references/a.md", "one\n"));
        String nineMib = "x".repeat(9 * 1024 * 1024);

        HttpResponse<String> first = put(code, "first.bin", nineMib, listEtag(code));
        assertThat(first.statusCode()).as("one 9 MiB file is under both ceilings: %s", first.body())
                .isEqualTo(200);
        HttpResponse<String> second = put(code, "second.bin", nineMib, listEtag(code));
        assertThat(second.statusCode()).as(second.body()).isEqualTo(400);
        assertThat(codeOf(second)).isEqualTo("invalid_upload");
        assertThat(issue(second)).isEqualTo("too_large");
    }

    /** PRD #3: the bytes are the promise, so CRLF and a missing final newline survive the round trip. */
    @Test
    void crlfAndAMissingFinalNewlineComeBackByteForByte() {
        String code = submitDraft(Map.of("references/a.md", "one\n"));
        String content = "first\r\nsecond\r\nthird";
        HttpResponse<String> written = put(code, "references/crlf.txt", content, listEtag(code));
        assertThat(written.statusCode()).as(written.body()).isEqualTo(200);

        HttpResponse<byte[]> read = getBytes(versionPath(code, "/files/references/crlf.txt"), token);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(new String(read.body(), StandardCharsets.UTF_8))
                .as("no normalisation: the CRLF pairs and the absent final newline are the file")
                .isEqualTo(content);
    }

    /**
     * A short code that collides is retried in a fresh transaction, with a fresh code.
     *
     * <p>The unit of retry is the whole insert: a primary-key collision aborts the PostgreSQL
     * transaction, so nothing inside it can be re-run. The seam is the id supplier — production
     * passes {@link ShortCode#generate}; here the first draw is the row that already exists.
     */
    /**
     * The legacy spellings, and the one that is deliberately not accepted (v7 TD §接口).
     *
     * <p>A row written before v7 has a 26-character ULID and must stay addressable both bare and as
     * {@code draft:<ULID>}; a new short code is addressable bare only, because {@code draft:} is the
     * status the address stopped carrying (ADR 0063).
     */
    @Test
    void aLegacyUlidIsAcceptedBareAndPrefixedButAShortCodeIsNotPrefixed() {
        String shortCode = submitDraft(Map.of("references/a.md", "one\n"));
        String skillId = jdbc.sql("SELECT id FROM skill WHERE name = 'pdf-tools'")
                .query(String.class).single();
        String userId = jdbc.sql("SELECT id FROM app_user WHERE handle = :handle")
                .param("handle", username).query(String.class).single();
        String legacy = Ulid.generate();
        jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, version, digest, file_count,
                                           total_bytes, changelog, source, submitted_by, submitted_at,
                                           state, state_at, title, description, frontmatter)
                VALUES (:id, :skill,
                        (SELECT COALESCE(MAX(number), 0) + 1 FROM skill_version
                         WHERE skill_id = :skill),
                        NULL, :digest, 0, 0, '', 'zip', :user, :at, 'draft', NULL, '', 'd', '{}')
                """).param("id", legacy).param("skill", skillId)
                .param("digest", "sha256:" + "2".repeat(64))
                .param("user", userId).param("at", "2026-10-20T00:00:00Z").update();

        assertThat(send(request(versionPath(legacy, "/files"), token).GET().build()).statusCode())
                .as("a bare ULID").isEqualTo(200);
        assertThat(send(request(versionPath("draft:" + legacy, "/files"), token).GET().build())
                .statusCode()).as("the legacy spelling").isEqualTo(200);
        assertThat(send(request(versionPath("draft:" + shortCode, "/files"), token).GET().build())
                .statusCode()).as("draft:<short> is not a spelling v7 accepts").isEqualTo(404);
    }

    @Test
    void aCollidingShortCodeIsDrawnAgainInANewTransaction() {
        String existingId = submitDraft(Map.of("references/a.md", "one\n"));
        String skillId = jdbc.sql("SELECT id FROM skill WHERE name = 'pdf-tools'")
                .query(String.class).single();
        String submittedBy = jdbc.sql("SELECT id FROM app_user WHERE handle = :handle")
                .param("handle", username).query(String.class).single();

        AtomicInteger draws = new AtomicInteger();
        VersionRepository colliding = new VersionRepository(jdbc, () -> draws.getAndIncrement() == 0
                ? existingId : ShortCode.generate());
        var recorder = new com.skillmasterai.usecase.DraftRecorder(transactions);

        String inserted = recorder.record(() -> colliding
                .insertIfAbsent(skillId, new SkillMetadata("pdf-tools", "t", "d", "{}"),
                        "retry", null, "sha256:" + "1".repeat(64), 1, 1, "api", submittedBy,
                        "2026-10-20T00:00:00Z", null)
                .orElseThrow());

        assertThat(draws.get()).as("the collision was seen, not swallowed").isEqualTo(2);
        assertThat(inserted).isNotEqualTo(existingId).matches("[0-9A-HJKMNP-TV-Z]{8}");
        assertThat(count("SELECT count(*) FROM skill_version WHERE id = :id",
                Map.of("id", inserted)))
                .as("one new row, and the colliding one untouched")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM skill_version WHERE id = :id",
                Map.of("id", existingId))).isEqualTo(1);
    }

    @Test
    void aWriteThatWouldDuplicateAnotherRowsContentIsRefused() {
        String published = submitDraft(Map.of("references/a.md", "one\n"));
        publish(published, "1.0.0");
        String other = submitDraft(Map.of("references/a.md", "two\n"));

        HttpResponse<String> clash = put(other, "references/a.md", "one\n", listEtag(other));
        assertThat(clash.statusCode()).as(clash.body()).isEqualTo(409);
        assertThat(codeOf(clash)).isEqualTo("content_already_exists");
        JsonNode details = JSON.readTree(clash.body()).get("error").get("details");
        assertThat(details.toString()).contains("1.0.0").contains("published");

        // The refusal changed nothing: the draft still holds what it held.
        HttpResponse<String> read = send(request(versionPath(other, "/files/references/a.md"),
                token).GET().build());
        assertThat(read.body()).isEqualTo("two\n");
    }

    @Test
    void writingTheRootSkillMdUpdatesTheVersionsSnapshotButNeverTheSkillRow() {
        String code = submitDraft(Map.of("references/a.md", "one\n"));
        String etag = listEtag(code);
        String document = "---\nname: pdf-tools\ndescription: changed description\n"
                + "title: Changed title\n---\n# body\n";

        assertThat(put(code, "SKILL.md", document, etag).statusCode()).isEqualTo(200);

        assertThat(jdbc.sql("SELECT description FROM skill_version WHERE id = :id")
                .param("id", code).query(String.class).single()).isEqualTo("changed description");
        assertThat(jdbc.sql("SELECT title FROM skill_version WHERE id = :id")
                .param("id", code).query(String.class).single()).isEqualTo("Changed title");
        assertThat(jdbc.sql("SELECT description FROM skill WHERE name = 'pdf-tools'")
                .query(String.class).single())
                .as("the skill row belongs to ADR 0049's metadata line, not to a draft write")
                .isEqualTo("d");

        // A root document that names another skill is refused, and the version keeps what it had.
        String rewritten = listEtag(code);
        HttpResponse<String> wrongName = put(code, "SKILL.md",
                "---\nname: some-other-skill\ndescription: d\n---\n", rewritten);
        assertThat(wrongName.statusCode()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT description FROM skill_version WHERE id = :id")
                .param("id", code).query(String.class).single()).isEqualTo("changed description");
    }

    @Test
    void aBodyBeyondTheSkillCeilingIsRefusedBeforeItIsBuffered() {
        String code = submitDraft(Map.of("references/a.md", "one\n"));
        byte[] oversized = new byte[(int) com.skillmasterai.modules.ingest.IngestLimits
                .STANDARD_MAX_BYTES + 1];
        HttpResponse<String> response = send(request(versionPath(code, "/files/big.bin"), token)
                .header(HttpHeaders.IF_MATCH, listEtag(code))
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(oversized)).build());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(codeOf(response)).isEqualTo("invalid_upload");
        assertThat(JSON.readTree(response.body()).get("error").get("details").toString())
                .contains("too_large");
    }

    @Test
    void theRegexEngineIsLinearAndRefusesTheConstructsItDoesNotHave() {
        // A nested quantifier against a long run of the same character is the classic catastrophic
        // backtracking case. RE2 (ADR 0066) makes it a linear scan; java.util.regex would not return.
        String code = submitDraft(Map.of("references/long.txt", "a".repeat(200_000) + "X\n"));
        HttpResponse<String> pathological = send(request(versionPath(code,
                "/grep?pattern=" + java.net.URLEncoder.encode("(a+)+$", StandardCharsets.UTF_8)),
                token).GET().build());
        assertThat(pathological.statusCode()).as(pathological.body()).isEqualTo(200);

        // Constructs RE2 does not implement are refused as bad patterns, not silently changed.
        HttpResponse<String> backreference = send(request(versionPath(code,
                "/grep?pattern=" + java.net.URLEncoder.encode("(a)\\1", StandardCharsets.UTF_8)),
                token).GET().build());
        assertThat(backreference.statusCode()).isEqualTo(400);
        assertThat(codeOf(backreference)).isEqualTo("invalid_request");

        HttpResponse<String> longPattern = send(request(versionPath(code,
                "/grep?pattern=" + "a".repeat(1001)), token).GET().build());
        assertThat(longPattern.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(longPattern.body()).get("error").get("details").toString())
                .contains("too_long");

        // The pattern's shape is checked before the row is resolved: a bad pattern is a 400 even for
        // a version that does not exist, so the answer never reveals whether it does.
        HttpResponse<String> longPatternMissingRow = send(request(versionPath("ZZZZZZZZ",
                "/grep?pattern=" + "a".repeat(1001)), token).GET().build());
        assertThat(longPatternMissingRow.statusCode()).isEqualTo(400);

        // **A counted repetition is the amplification the length cap cannot see**: seven characters
        // buy a thousand states, and on a 16 MiB line that ran for about a minute (ADR 0067). It is
        // refused before the draft is read.
        HttpResponse<String> amplified = send(request(versionPath(code,
                "/grep?pattern=" + java.net.URLEncoder.encode("a{999}b", StandardCharsets.UTF_8)),
                token).GET().build());
        assertThat(amplified.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(amplified.body()).get("error").get("details").toString())
                .contains("too_expensive");

        // The bound covers the counted range too: a maximum of 101 states is over the same limit.
        HttpResponse<String> bounded = send(request(versionPath(code,
                "/grep?pattern=" + java.net.URLEncoder.encode("a{10,101}b", StandardCharsets.UTF_8)),
                token).GET().build());
        assertThat(bounded.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(bounded.body()).get("error").get("details").toString())
                .contains("too_expensive");

        // **Nesting multiplies at compile time, before any permission check.** Four counts of 100
        // are a hundred million nodes: 27 characters that used to exhaust the heap. The cumulative
        // bound
        // refuses it without compiling.
        HttpResponse<String> nested = send(request(versionPath(code,
                "/grep?pattern=" + java.net.URLEncoder.encode(
                        "(((a{100}){100}){100}){100}", StandardCharsets.UTF_8)),
                token).GET().build());
        assertThat(nested.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(nested.body()).get("error").get("details").toString())
                .contains("too_expensive");

        // **A zero count does not zero the bound.** RE2 still expands the counts around `{0}`, so
        // multiplying by 0 here used to let four layers through to a compile-time OOM.
        HttpResponse<String> zeroCount = send(request(versionPath(code,
                "/grep?pattern=" + java.net.URLEncoder.encode(
                        "((((a{0}){100}){100}){100}){100}", StandardCharsets.UTF_8)),
                token).GET().build());
        assertThat(zeroCount.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(zeroCount.body()).get("error").get("details").toString())
                .contains("too_expensive");

        // The bound includes the length of what is repeated: a 969-character pattern with two
        // counts of 100 has a small product and a ten-million-node expansion.
        HttpResponse<String> longRepeat = send(request(versionPath(code,
                "/grep?pattern=" + java.net.URLEncoder.encode(
                        "((" + "a".repeat(955) + "){100}){100}", StandardCharsets.UTF_8)),
                token).GET().build());
        assertThat(longRepeat.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(longRepeat.body()).get("error").get("details").toString())
                .contains("too_expensive");

        // Quoted text is literal: `{999}` inside \Q…\E is not a repetition, so this is searched.
        HttpResponse<String> quoted = send(request(versionPath(code,
                "/grep?pattern=" + java.net.URLEncoder.encode("\\Qa{999}\\E", StandardCharsets.UTF_8)),
                token).GET().build());
        assertThat(quoted.statusCode()).as(quoted.body()).isEqualTo(200);

        // Below the cumulative bound, the automaton can still be large enough to scan for a long
        // time: `(a{100}){100}b` is 10,000 states, and the long line is 200,000 `a`s. The request's
        // deadline is what bounds it.
        String manyCounts = "(a{100}){100}b";
        long began = System.nanoTime();
        HttpResponse<String> expensive = send(request(versionPath(code,
                "/grep?pattern=" + java.net.URLEncoder.encode(manyCounts, StandardCharsets.UTF_8)),
                token).GET().build());
        long tookMillis = (System.nanoTime() - began) / 1_000_000;
        assertThat(expensive.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(expensive.body()).get("error").get("details").toString())
                .contains("too_expensive");
        assertThat(tookMillis).as("the deadline stops the scan").isLessThan(15_000);
    }

    /**
     * A file of short lines is bounded by the deadline too.
     *
     * <p>The per-character check only runs once the engine has read 1,024 characters; a line of 100
     * never gets there, so a draft made of many short lines used to scan for as long as the automaton
     * wanted. The line-boundary check is what closes that (ADR 0067).
     */
    @Test
    void manyShortLinesStillStopAtTheDeadline() {
        String code = submitDraft(Map.of("references/lines.txt",
                ("a".repeat(100) + "\n").repeat(100_000)));
        long began = System.nanoTime();
        HttpResponse<String> expensive = send(request(versionPath(code,
                "/grep?pattern=" + java.net.URLEncoder.encode("(a{100}){100}b", StandardCharsets.UTF_8)),
                token).GET().build());
        long tookMillis = (System.nanoTime() - began) / 1_000_000;
        assertThat(expensive.statusCode()).as(expensive.body()).isEqualTo(400);
        assertThat(JSON.readTree(expensive.body()).get("error").get("details").toString())
                .contains("too_expensive");
        assertThat(tookMillis).as("the deadline stops the scan").isLessThan(15_000);
    }

    /**
     * A fixed literal is searched in linear time regardless of its length.
     *
     * <p>The regex engine's cost grows with the automaton, so the same 1,000-character literal used
     * to hold this request for over a minute (ADR 0067). Correctness matters as much as the bound:
     * the match still has to be found, and `-i` still has to fold it.
     */
    @Test
    void aLongFixedLiteralIsFoundInEveryCaseMode() {
        String code = submitDraft(Map.of("references/long.txt", "a".repeat(200_000) + "b\n"));
        String needle = "a".repeat(999) + "b";

        HttpResponse<String> exact = send(request(versionPath(code,
                "/grep?pattern=" + java.net.URLEncoder.encode(needle, StandardCharsets.UTF_8)
                        + "&fixed=true"), token).GET().build());
        assertThat(exact.statusCode()).as(exact.body()).isEqualTo(200);
        assertThat(JSON.readTree(exact.body()).get("hits")).hasSize(1);

        HttpResponse<String> folded = send(request(versionPath(code,
                "/grep?pattern=" + java.net.URLEncoder.encode(needle.toUpperCase(java.util.Locale.ROOT),
                        StandardCharsets.UTF_8) + "&fixed=true&ignore_case=true"),
                token).GET().build());
        assertThat(folded.statusCode()).as(folded.body()).isEqualTo(200);
        assertThat(JSON.readTree(folded.body()).get("hits")).hasSize(1);
    }

    @Test
    void binaryBytesAreStoredAsBinaryAndGrepSkipsThem() {
        String code = submitDraft(Map.of("references/a.md", "needle here\n"));
        byte[] binary = {(byte) 0xff, (byte) 0xfe, 'b', 'i', 'n'};
        HttpResponse<String> written = send(request(versionPath(code, "/files/blob.bin"), token)
                .header(HttpHeaders.IF_MATCH, listEtag(code))
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(binary)).build());
        assertThat(written.statusCode()).as(written.body()).isEqualTo(200);

        HttpResponse<String> listed = send(request(versionPath(code, "/files"), token).GET().build());
        assertThat(JSON.readTree(listed.body()).get("files").toString())
                .contains("\"relpath\":\"blob.bin\",\"size\":5,\"is_binary\":true");

        HttpResponse<String> grep = send(request(versionPath(code, "/grep?pattern=b"),
                token).GET().build());
        assertThat(grep.statusCode()).as(grep.body()).isEqualTo(200);
        assertThat(grep.body()).as("a binary file is never a text hit").doesNotContain("blob.bin");
    }

    @Test
    void aSuccessfulWriteLeavesOneAuditRowAndARefusalLeavesNone() {
        String code = submitDraft(Map.of("references/a.md", "one\n"));
        assertThat(put(code, "references/b.md", "two\n", listEtag(code)).statusCode()).isEqualTo(200);
        assertThat(put(code, "references/c.md", "x", "\"sha256:" + "0".repeat(64) + "\"")
                .statusCode()).isEqualTo(409);

        String detail = jdbc.sql("SELECT detail FROM audit_event WHERE action = 'version_file_write'"
                + " ORDER BY id DESC").query(String.class).single();
        assertThat(detail)
                .contains("\"op\":\"write\"")
                .contains("\"path\":\"references/b.md\"")
                .contains("\"code\":\"" + code + "\"")
                .doesNotContain("two\n")
                .doesNotContain("\\u0000");
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'version_file_write'"))
                .as("a refused write writes nothing, audit included")
                .isEqualTo(1);
    }

    /**
     * The invoke rung cannot enumerate: 403 before the pin is resolved, whichever pin it carries.
     *
     * <p>This is v7's delivery condition #31, and the failure it guards against has a shape — a
     * 404 here would tell a caller whose address was right that the skill is missing.
     */
    /**
     * ADR 0098's rung boundary, on both sides: `browse` reaches a published version and not a draft,
     * `write` reaches the draft. Before this, every grant below `write` was refused for every state,
     * so a suite that only exercised the owner and `invoke` would pass with the old rule intact.
     */
    @Test
    void browseGrepsAPublishedVersionAndWriteGrepsItsDraft() {
        String code = submitDraft(Map.of("references/a.md", "needle\n"));
        publish(code, "1.0.0");

        String reader = randomUsername();
        String readerToken = tokenFor(registerAndSignIn(reader, randomPhone()));
        assertThat(shareWith(reader, "browse").statusCode()).isEqualTo(201);

        HttpResponse<String> published = send(request(
                "/api/v1/author/skills/" + username + "/pdf-tools/versions/1.0.0/grep?pattern=needle",
                readerToken).GET().build());
        assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
        assertThat(JSON.readTree(published.body()).get("hits")).hasSize(1);

        String later = submitDraft(Map.of("references/b.md", "needle\n"));
        HttpResponse<String> draftAsReader = send(request(versionPath(later, "/grep?pattern=needle"),
                readerToken).GET().build());
        assertThat(draftAsReader.statusCode()).as("a draft is not visible to browse")
                .isEqualTo(404);

        String writer = randomUsername();
        String writerToken = tokenFor(registerAndSignIn(writer, randomPhone()));
        assertThat(shareWith(writer, "write").statusCode()).isEqualTo(201);
        HttpResponse<String> draftAsWriter = send(request(versionPath(later, "/grep?pattern=needle"),
                writerToken).GET().build());
        assertThat(draftAsWriter.statusCode()).as(draftAsWriter.body()).isEqualTo(200);
        assertThat(JSON.readTree(draftAsWriter.body()).get("hits")).hasSize(1);
    }

    @Test
    void anInvokeGrantCannotListFilesAndCanStillReadOne() {
        String code = submitDraft(Map.of("references/a.md", "one\n"));
        publish(code, "1.0.0");

        String friend = randomUsername();
        String friendToken = tokenFor(registerAndSignIn(friend, randomPhone()));
        assertThat(shareInvoke(friend).statusCode()).isEqualTo(201);

        HttpResponse<String> listing = send(request(
                "/api/v1/author/skills/" + username + "/pdf-tools/versions/" + code + "/files",
                friendToken).GET().build());
        assertThat(listing.statusCode()).as(listing.body()).isEqualTo(403);
        assertThat(codeOf(listing)).isEqualTo("forbidden");

        HttpResponse<String> count = send(request(
                "/api/v1/author/skills/" + username + "/pdf-tools/versions/" + code + "/count",
                friendToken).GET().build());
        assertThat(count.statusCode()).as("counting is enumeration too").isEqualTo(403);

        // grep is a `browse` act for the same reason the list is: it answers "which files contain
        // this", one file at a time. ADR 0098 widened the states it reaches, not the rung it needs.
        HttpResponse<String> grep = send(request(
                "/api/v1/author/skills/" + username + "/pdf-tools/versions/" + code
                        + "/grep?pattern=one", friendToken).GET().build());
        assertThat(grep.statusCode()).as(grep.body()).isEqualTo(403);

        // The file itself is reachable on the consumption plane, by a pin the caller holds — that
        // is the half of ADR 0047 `invoke` keeps ("take the one you can name").
        HttpResponse<String> single = send(request(
                "/api/v1/skills/" + username + "/pdf-tools/files/references/a.md", friendToken)
                .header("X-Skill-Version", "1.0.0").GET().build());
        assertThat(single.statusCode()).as(single.body()).isEqualTo(200);
        assertThat(single.body()).isEqualTo("one\n");
    }

    /* --------------------------------------------------------------- helpers */

    private String submitDraft(Map<String, String> extra) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n");
        files.putAll(extra);
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(files))
                .field("name", "draft " + extra.size());
        HttpResponse<String> response = send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher()).build());
        assertThat(response.statusCode()).as("submit failed: %s", response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("version").get("draft_id").asText();
    }

    /** A submission that is expected to be refused, so it cannot use {@link #submitDraft}. */
    private HttpResponse<String> submitRaw(Map<String, String> extra, String name) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n");
        files.putAll(extra);
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", Zips.ofText(files))
                .field("name", name);
        return send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher()).build());
    }

    private String versionPath(String code, String suffix) {
        return "/api/v1/author/skills/" + username + "/pdf-tools/versions/" + code + suffix;
    }

    private HttpResponse<String> put(String code, String relpath, String content, String ifMatch) {
        return send(request(versionPath(code, "/files/" + relpath), token)
                .header(HttpHeaders.IF_MATCH, ifMatch)
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(
                        content.getBytes(StandardCharsets.UTF_8)))
                .build());
    }

    private String listEtag(String code) {
        HttpResponse<String> listed = send(request(versionPath(code, "/files"), token).GET().build());
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        return listed.headers().firstValue(HttpHeaders.ETAG).orElseThrow();
    }

    private String codeOf(HttpResponse<String> response) {
        return JSON.readTree(response.body()).get("error").get("code").asText();
    }

    private HttpResponse<String> shareInvoke(String handle) {
        return shareWith(handle, "invoke");
    }

    private HttpResponse<String> shareWith(String handle, String role) {
        return send(request("/api/v1/skills/" + username + "/pdf-tools/grants", token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("handle", handle, "role", role))))
                .build());
    }

    private void publish(String code, String releaseName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", code);
        body.put("release_name", releaseName);
        HttpResponse<String> response = webPost(
                "/web/skills/" + username + "/pdf-tools/publish", json(body));
        assertThat(response.statusCode()).as("publish failed: %s", response.body()).isEqualTo(200);
    }
}
