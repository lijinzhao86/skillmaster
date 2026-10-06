package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Browser;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The author's plane, end to end (ADR 0031): the skill's versions with their states, the drafts the
 * consumption plane cannot see, and the comparison between two of them.
 *
 * <p><strong>Everything here is submitted for real and published by a browser.</strong> The numbers,
 * the digests and the states are all the server's; a fixture that wrote its own rows could be made to
 * look right while the two operations that produce them were broken, and it is exactly those two that
 * this change split.
 *
 * <p>The other half of every case is the consumption plane, which is why several of these assert on
 * both. "A draft is visible here and not there" is one fact about the split, and asserting only the
 * visible half would pass just as well if the leak were still open.
 */
@Sql("/sql/truncate-business-tables.sql")
class WebSkillsIT extends AbstractAccountIT {

    private static final String BASE = "/web/skills";

    /** The one account this test acts as, on both planes; see {@link #submit}. */
    private String username;
    private String token;

    @BeforeEach
    void signUp() {
        username = randomUsername();
        token = tokenFor(registerAndSignIn(username, randomPhone()));
    }

    @Test
    void theListingShowsASkillNothingIsLiveFromYet() {
        submit(first());

        JsonNode skills = body(webGet(BASE)).get("skills");

        assertThat(skills).hasSize(1);
        JsonNode skill = skills.get(0);
        assertThat(skill.get("namespace").asText())
                .as("carried per row so a client can build a link without having read the session "
                        + "first and been right about how the two relate")
                .isEqualTo(username);
        assertThat(skill.get("name").asText()).isEqualTo("pdf-tools");
        assertThat(skill.get("current").isNull())
                .as("null rather than a version: nothing has been published, and version 0 does not "
                        + "exist — a number here would be an address a client could try to fetch")
                .isTrue();
        assertThat(skill.get("drafts").asInt()).isEqualTo(1);
        assertThat(skill.get("title").asText())
                .as("the metadata is the version's, which is what a submission carries")
                .isEqualTo("PDF Tools");
        assertThat(skill.get("latest_submitted_at").asText()).isNotBlank();
    }

    /**
     * A card is named after the newest submission while nothing is live, not after the first one.
     *
     * {@code skill.title} is written when the skill is created and when a version is published, and
     * **never by a submit** (ADR 0031 — a submission must not change what the consumption plane
     * shows). For a skill nothing has been published from, that leaves the row's copy frozen at the
     * first submission for ever, so reading it here named the skill after a title no version holds any
     * more, while the detail page the card opens — which follows the pointer, and the newest
     * non-discarded version when there is none — showed the new one.
     */
    @Test
    void theListingNamesASkillAfterItsNewestSubmissionWhileNothingIsLive() {
        submit(first());
        submit(zip("第二版", "PDF Toolkit"));

        JsonNode skill = body(webGet(BASE)).get("skills").get(0);

        assertThat(skill.get("current").isNull())
                .as("nothing has been published, which is the case the fallback exists for")
                .isTrue();
        assertThat(skill.get("title").asText()).isEqualTo("PDF Toolkit");
        assertThat(body(webGet(BASE + "/" + username + "/pdf-tools")).get("title").asText())
                .as("and the page this card opens says the same thing the card does")
                .isEqualTo("PDF Toolkit");
    }

    @Test
    void theSameAddressAnswersOnOnePlaneAndNotTheOther() {
        submit(first());

        assertThat(webGet(BASE + "/" + username + "/pdf-tools").statusCode())
                .as("the author reads their own draft")
                .isEqualTo(200);
        assertThat(get(address(), token).statusCode())
                .as("and a consumer holding a token does not, which is the whole point of the split")
                .isEqualTo(404);
    }

    @Test
    void everyVersionComesBackWithWhatWasDecidedAboutIt() {
        submit(first());
        publish(1);
        submit(second());
        discard(2);

        JsonNode detail = body(webGet(BASE + "/" + username + "/pdf-tools"));

        assertThat(detail.get("versions")).hasSize(2);
        JsonNode discarded = detail.get("versions").get(0);
        assertThat(discarded.get("number").asInt()).isEqualTo(2);
        assertThat(discarded.get("state").asText())
                .as("a discarded version is shown as discarded rather than made to look like it "
                        + "never existed")
                .isEqualTo("discarded");
        assertThat(discarded.get("state_at").asText()).isNotBlank();

        JsonNode live = detail.get("versions").get(1);
        assertThat(live.get("number").asInt()).isEqualTo(1);
        assertThat(live.get("state").asText()).isEqualTo("published");
        assertThat(live.get("is_current").asBoolean()).isTrue();

        assertThat(detail.get("version").get("number").asInt())
                .as("the bare address follows the pointer, and the pointer still names version 1")
                .isEqualTo(1);
    }

    /**
     * A replay answers with the row that already holds that digest, and that row is not always a draft.
     *
     * ADR 0005's idempotence is one answer for one piece of content, and the unique constraint is on
     * `(skill_id, digest)` rather than on the state — so submitting something the server already has
     * names whichever version it holds, whatever has happened to it since. A client cannot derive
     * that: `created: false` says the content did not move and nothing about where it stands, and the
     * guess that reads best — "a submission leaves a draft, so go and publish it" — is the one that is
     * false here. *This* row is the published one, and the CLI prints its instruction from this field.
     */
    @Test
    void aReplayOfPublishedContentSaysSoRatherThanCallingItADraft() {
        submit(first());
        publish(1);

        JsonNode body = replay(first());

        assertThat(body.get("created").asBoolean()).isFalse();
        assertThat(body.get("version").get("number").asInt()).isEqualTo(1);
        assertThat(body.get("version").get("state").asText()).isEqualTo("published");
    }

    /** The same for a version that was thrown away — the row still holds the digest. */
    @Test
    void aReplayOfDiscardedContentSaysSoToo() {
        submit(first());
        discard(1);

        JsonNode body = replay(first());

        assertThat(body.get("version").get("number").asInt())
                .as("nothing new was written: the discarded row still holds that content")
                .isEqualTo(1);
        assertThat(body.get("version").get("state").asText()).isEqualTo("discarded");
    }

    /**
     * Publishing one version from several requests at once: every one of them is a success.
     *
     * <p>Each request reads the version as a draft and each tries to make it published. One wins and
     * the others' update matches no row — which is *also* exactly what a concurrent discard produces,
     * and the guard (`state = 'draft'`) cannot tell the two apart. Reading the row again is what does.
     * Without that, a double click on 上线 answers 400 「was discarded while being published」 about a
     * version that is live and was never discarded. That is the same misreading the rollback fix made
     * in the other direction, and this is the test that fails — with that 400 — if the second read
     * ever goes away.
     *
     * <p>Released together so the requests genuinely overlap rather than queueing in the client. The
     * assertion is an invariant rather than an interleaving: a correct implementation answers 200 to
     * all of them however the threads run.
     */
    @Test
    void concurrentPublishesOfTheSameVersionAllSucceed() throws Exception {
        submit(first());

        assertThat(concurrently(8, "publish", 1))
                .as("a double click is not a discard")
                .containsOnly(200);

        assertThat(body(webGet(BASE + "/" + username + "/pdf-tools")).get("version").get("number").asInt())
                .as("and the version it was about is the live one")
                .isEqualTo(1);
    }

    /**
     * The same race one operation over, and the same misreading.
     *
     * <p>`markDiscarded`'s guard is `state = 'draft'`, and zero rows updated means the version stopped
     * being a draft between the read and the update — which a concurrent discard of that same version
     * does just as readily as a concurrent publish. Reading only the zero told a double click on 丢弃
     * that its version had just been *published*, which is false and alarming both.
     */
    @Test
    void concurrentDiscardsOfTheSameVersionAllSucceed() throws Exception {
        submit(first());

        assertThat(concurrently(8, "discard", 1))
                .as("a double click on 丢弃 is not a publish")
                .containsOnly(200);
    }

    /**
     * A skill whose every version was discarded still opens.
     *
     * <p>The listing shows it — it is the author's work, and "everything here was thrown away" is a
     * fact about it rather than a reason to hide it — so the address that row links to has to resolve.
     * It did not: `Latest` fell back to the newest version that was *not* discarded, which for this
     * skill is none, and the page answered 404 while the list was showing the skill by name.
     */
    @Test
    void aSkillWhoseEveryVersionWasDiscardedStillOpens() {
        submit(first());
        discard(1);

        JsonNode detail = body(webGet(BASE + "/" + username + "/pdf-tools"));

        assertThat(detail.get("version").get("number").asInt()).isEqualTo(1);
        assertThat(detail.get("version").get("state").asText()).isEqualTo("discarded");

        JsonNode listed = body(webGet(BASE)).get("skills").get(0);
        assertThat(listed.get("current").isNull()).as("nothing is live").isTrue();
        assertThat(listed.get("latest_submitted_at").isNull())
                .as("and there is no submission time left to report")
                .isTrue();
    }

    @Test
    void aDraftIsReadableThroughItsOwnAddress() {
        submit(first());
        publish(1);
        submit(second());

        JsonNode detail = body(webGet(BASE + "/" + username + "/pdf-tools@2"));

        assertThat(detail.get("version").get("number").asInt()).isEqualTo(2);
        assertThat(detail.get("version").get("state").asText()).isEqualTo("draft");
        assertThat(detail.get("version").get("is_current").asBoolean()).isFalse();
        assertThat(detail.get("version").get("submitted_at").asText()).isNotBlank();
        assertThat(detail.get("title").asText())
                .as("a pinned read describes the version it pinned, not the one that is live")
                .isEqualTo("PDF Tools");

        String bodyUri = detail.get("resources").get("body").asText();
        assertThat(bodyUri).isEqualTo(BASE + "/" + username + "/pdf-tools@2/body");
        assertThat(webGet(bodyUri).body())
                .as("and the address the response advertises really serves that version")
                .contains("第二版");
    }

    /**
     * The file list a version's page shows has to be openable, and on the author's plane that means
     * a file of a <em>draft</em> — the API plane's L3 resolves only published versions.
     */
    @Test
    void everyFileOfADraftCanBeReadAndNothingElseCan() {
        submit(first());
        publish(1);
        submit(second());

        assertThat(webGet(BASE + "/" + username + "/pdf-tools@2/files/references/notes.md").body())
                .as("a draft's reference file is readable here")
                .isEqualTo("unchanged\n");
        assertThat(webGet(BASE + "/" + username + "/pdf-tools@2/files/SKILL.md").body())
                .as("and so is the body, by the same route")
                .contains("第二版");

        HttpResponse<String> missing = webGet(BASE + "/" + username + "/pdf-tools@2/files/nope.md");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(body(missing).get("error").get("code").asText())
                .as("a version that resolved without listing this relpath is its own code — "
                        + "§4.1 keeps the two nothings apart")
                .isEqualTo("file_not_found");

        assertThat(webGet(BASE + "/" + username + "/pdf-tools@99/files/SKILL.md").statusCode())
                .as("while a version that does not exist is the address's 404")
                .isEqualTo(404);
    }

    @Test
    void beforeAnythingIsPublishedTheBaselineIsNothing() {
        submit(first());

        JsonNode diff = body(webGet(BASE + "/" + username + "/pdf-tools/diff"));

        assertThat(diff.get("from").isNull()).as("no baseline rather than version 0").isTrue();
        assertThat(diff.get("to").asInt()).isEqualTo(1);
        assertThat(diff.get("truncated").asBoolean()).isFalse();
        assertThat(diff.get("files")).extracting(file -> file.get("status").asText())
                .as("with nothing to compare against, every file of the version is new — which is "
                        + "also the honest answer to 'what would publishing this add'")
                .containsOnly("added");
    }

    @Test
    void theDiffOfADraftAgainstLiveIsWhatPublishingItWouldChange() {
        submit(first());
        publish(1);
        submit(second());

        JsonNode diff = body(webGet(BASE + "/" + username + "/pdf-tools/diff?to=2"));

        assertThat(diff.get("from").asInt())
                .as("omitting from compares against what is live, which is the question a person "
                        + "opening a draft has")
                .isEqualTo(1);
        assertThat(diff.get("to").asInt()).isEqualTo(2);

        JsonNode skill = fileNode(diff, "SKILL.md");
        assertThat(skill.get("status").asText()).isEqualTo("modified");
        assertThat(skill.get("binary").asBoolean()).isFalse();
        assertThat(skill.get("added").asInt()).isEqualTo(1);
        assertThat(skill.get("removed").asInt()).isEqualTo(1);

        // Read as a diff rather than by index: the marker is one line of a file whose frontmatter
        // is context, so where in the hunk it sits is the algorithm's business and not this test's.
        List<String> lines = new ArrayList<>();
        skill.get("hunks").get(0).get("lines").forEach(line -> lines.add(line.asText()));
        assertThat(lines)
                .as("the marker changed, and the frontmatter around it is context")
                .contains("-第一版", "+第二版")
                .allSatisfy(line -> assertThat(List.of(' ', '-', '+'))
                        .as("every line carries unified diff's own prefix, which is what lets a "
                                + "client that renders plain text still show a correct diff")
                        .contains(line.charAt(0)));

        assertThat(fileNode(diff, "references/notes.md"))
                .as("a file whose content did not change is not part of the answer")
                .isNull();
    }

    @Test
    void anyTwoVersionsCanBeCompared() {
        submit(first());
        publish(1);
        submit(second());
        publish(2);
        submit(third());

        JsonNode diff = body(webGet(BASE + "/" + username + "/pdf-tools/diff?from=1&to=3"));

        assertThat(diff.get("from").asInt()).isEqualTo(1);
        assertThat(diff.get("to").asInt()).isEqualTo(3);
        assertThat(diff.get("files")).extracting(file -> file.get("relpath").asText())
                .as("the version in between is not part of this comparison")
                .containsExactly("SKILL.md");
    }

    @Test
    void aVersionInTheAddressIsTheDefaultTarget() {
        submit(first());
        publish(1);
        submit(second());

        assertThat(body(webGet(BASE + "/" + username + "/pdf-tools@2/diff")).get("to").asInt())
                .as("the page's own address is enough to ask what publishing this version would "
                        + "change; the query only exists to override it")
                .isEqualTo(2);
    }

    @Test
    void discardingIsOneWayAndOnlyADraftCanBeDiscarded() {
        submit(first());
        publish(1);
        submit(second());

        assertThat(webPost(BASE + "/" + username + "/pdf-tools/discard",
                json(Map.of("number", 2))).statusCode()).isEqualTo(200);

        HttpResponse<String> republished = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("number", 2)));
        assertThat(republished.statusCode())
                .as("a version the author threw away is not one they can later put live by number")
                .isEqualTo(400);
        assertThat(body(republished).get("error").get("code").asText()).isEqualTo("invalid_request");
        assertThat(body(republished).get("error").get("message").asText()).contains("discarded");

        HttpResponse<String> discardedAgain = webPost(BASE + "/" + username + "/pdf-tools/discard",
                json(Map.of("number", 2)));
        assertThat(discardedAgain.statusCode()).as("and the discard itself does not repeat").isEqualTo(400);

        assertThat(body(webGet(BASE + "/" + username + "/pdf-tools")).get("version").get("number").asInt())
                .as("through all of that the live version never moved")
                .isEqualTo(1);
    }

    /**
     * Rolling back: publishing an older version that has already been published once.
     *
     * <p>This is a different case from publishing a draft and from re-publishing the current version,
     * and it is the one the gateway forces whenever its source reverts. It was **not** covered by any
     * test until a real server was started against a real database and failed to boot on exactly this
     * — the pointer move is all that should happen, while the state and the original live moment stay
     * as they were.
     */
    @Test
    void publishingAnOlderPublishedVersionRollsThePointerBack() {
        submit(first());
        publish(1);
        String firstLiveAt = body(webGet(BASE + "/" + username + "/pdf-tools@1"))
                .get("version").get("state_at").asText();

        submit(second());
        publish(2);
        assertThat(body(webGet(BASE + "/" + username + "/pdf-tools")).get("version").get("number").asInt())
                .isEqualTo(2);

        HttpResponse<String> rolledBack = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("number", 1)));

        assertThat(rolledBack.statusCode()).as("rollback: %s", rolledBack.body()).isEqualTo(200);
        assertThat(body(rolledBack).get("changed").asBoolean()).isTrue();
        assertThat(body(rolledBack).get("live_at").asText())
                .as("when that version first went live, not when it was asked for again")
                .isEqualTo(firstLiveAt);

        JsonNode detail = body(webGet(BASE + "/" + username + "/pdf-tools"));
        assertThat(detail.get("version").get("number").asInt())
                .as("the bare address follows the pointer, which has moved backwards")
                .isEqualTo(1);
        assertThat(detail.get("versions"))
                .as("and the version it moved off is still published, not reopened as a draft")
                .extracting(version -> version.get("state").asText())
                .containsOnly("published");

        assertThat(get(address(), token).statusCode())
                .as("consumers get the older version now, which is what rolling back means")
                .isEqualTo(200);
        assertThat(webGet(BASE + "/" + username + "/pdf-tools/body").body()).contains("第一版");
        assertThat(get(address() + "@2", token).statusCode())
                .as("and the version that was current a moment ago is still addressable by its number")
                .isEqualTo(200);
    }

    @Test
    void publishingAVersionThatIsAlreadyLiveWritesNothing() {
        submit(first());
        publish(1);

        HttpResponse<String> again = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("number", 1)));

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(body(again).get("changed").asBoolean())
                .as("the state that was asked for, reached twice")
                .isFalse();
    }

    @Test
    void anotherNamespacesSkillIsNotFound() {
        submit(first());

        HttpResponse<String> notMine = webGet(BASE + "/other/pdf-tools");
        HttpResponse<String> nothingHere = webGet(BASE + "/" + username + "/nothing-here");

        assertThat(notMine.statusCode()).isEqualTo(404);
        assertThat(notMine.body())
                .as("on this plane too: 'not yours' and 'not there' have to be the same nothing, or "
                        + "an author could probe for other people's skills")
                .isEqualTo(nothingHere.body());
    }

    @Test
    void aVersionThatDoesNotExistIsNotFound() {
        submit(first());

        assertThat(webGet(BASE + "/" + username + "/pdf-tools@99").statusCode()).isEqualTo(404);
        assertThat(webGet(BASE + "/" + username + "/pdf-tools/diff?from=99").statusCode())
                .as("an invented baseline is not a comparison against nothing — it is a miss")
                .isEqualTo(404);
        assertThat(webGet(BASE + "/" + username + "/pdf-tools/diff?to=99").statusCode()).isEqualTo(404);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private String address() {
        return "/api/v1/skills/" + username + "/pdf-tools";
    }

    /** Submits over the API, as the same account's client, and returns nothing but the io. */
    private JsonNode submit(byte[] zip) {
        Multipart multipart = Multipart.create().file("file", "pdf-tools.zip", zip);
        HttpResponse<String> submitted = send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
        assertThat(submitted.statusCode()).as("submit failed: %s", submitted.body()).isEqualTo(201);
        return JSON.readTree(submitted.body());
    }

    /**
     * Runs one write {@code times} at once and returns every status code.
     *
     * <p>Released together so the requests genuinely overlap rather than queueing in the client. The
     * session cookie and the CSRF token are read once and carried by every thread rather than shared
     * through the test's cookie jar, which is not built for concurrent use — what is being raced is
     * the server's two writes, not the client's.
     */
    private List<Integer> concurrently(int times, String action, int number) throws Exception {
        String csrf = csrfToken();
        String cookie = "SKILLMASTER_SESSION=" + cookieValue("SKILLMASTER_SESSION")
                + "; " + Browser.CSRF_COOKIE + "=" + csrf;
        String payload = json(Map.of("number", number));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(times)) {
            List<Future<Integer>> futures = IntStream.range(0, times)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return send(HttpRequest
                                .newBuilder(uri(BASE + "/" + username + "/pdf-tools/" + action))
                                .header(HttpHeaders.COOKIE, cookie)
                                .header(Browser.CSRF_HEADER, csrf)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .POST(HttpRequest.BodyPublishers.ofString(payload))
                                .build()).statusCode();
                    }))
                    .toList();
            start.countDown();

            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get(30, TimeUnit.SECONDS));
            }
            return codes;
        }
    }

    /** A second submission of content the server already holds: ADR 0005's replay, answered 200. */
    private JsonNode replay(byte[] zip) {
        Multipart multipart = Multipart.create().file("file", "pdf-tools.zip", zip);
        HttpResponse<String> response = send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
        assertThat(response.statusCode()).as("a replay failed: %s", response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private void publish(int number) {
        HttpResponse<String> published = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("number", number)));
        assertThat(published.statusCode()).as("publish failed: %s", published.body()).isEqualTo(200);
    }

    private void discard(int number) {
        HttpResponse<String> discarded = webPost(BASE + "/" + username + "/pdf-tools/discard",
                json(Map.of("number", number)));
        assertThat(discarded.statusCode()).as("discard failed: %s", discarded.body()).isEqualTo(200);
    }

    private static byte[] first() {
        return zip("第一版");
    }

    private static byte[] second() {
        return zip("第二版");
    }

    private static byte[] third() {
        return zip("第三版");
    }

    /**
     * One skill, wrapped in a directory named after it, with a marker in its body and one reference
     * file that never changes — so a comparison has one modified file and one that is not in the
     * answer at all.
     */
    private static byte[] zip(String marker) {
        return zip(marker, "PDF Tools");
    }

    /** The same skill with the title changed — the one thing a re-submission can differ in. */
    private static byte[] zip(String marker, String title) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pdf-tools/SKILL.md",
                "---\nname: pdf-tools\ntitle: " + title + "\n"
                        + "description: a skill for the author-plane tests\n---\n" + marker + "\n");
        files.put("pdf-tools/references/notes.md", "unchanged\n");
        return Zips.ofText(files);
    }

    /** One file's node in a diff, or null when the comparison does not mention it. */
    private static JsonNode fileNode(JsonNode diff, String relpath) {
        for (JsonNode file : diff.get("files")) {
            if (file.get("relpath").asText().equals(relpath)) {
                return file;
            }
        }
        return null;
    }
}
