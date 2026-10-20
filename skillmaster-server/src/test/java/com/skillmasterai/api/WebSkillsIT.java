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
 * <p><strong>Everything here is submitted for real and published by a browser.</strong> The version
 * names (declared in the fixture's {@code SKILL.md}), the digests and the states are all the server's;
 * a fixture that wrote its own rows could be made to look right while the two operations that produce
 * them were broken, and it is exactly those two that this change split.
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
                .as("null rather than a version: nothing has been published, and a placeholder here "
                        + "would be an address a client could try to fetch")
                .isTrue();
        assertThat(skill.get("drafts").asInt()).isEqualTo(1);
        assertThat(skill.get("title").asText())
                .as("the metadata is the version's, which is what a submission carries")
                .isEqualTo("PDF Tools");
        assertThat(skill.get("updated_at").asText()).isNotBlank();
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
        submit(zip("2.0.0", "第二版", "PDF Toolkit"));

        JsonNode skill = body(webGet(BASE)).get("skills").get(0);

        assertThat(skill.get("current").isNull())
                .as("nothing has been published, which is the case the fallback exists for")
                .isTrue();
        assertThat(skill.get("title").asText()).isEqualTo("PDF Toolkit");
        assertThat(body(webGet(BASE + "/" + username + "/pdf-tools")).get("title").asText())
                .as("and the page this card opens says the same thing the card does")
                .isEqualTo("PDF Toolkit");
    }

    /**
     * V21's projections, driven through the operations that must maintain them (test-plan T15).
     *
     * <p><strong>The card's time is the current published version's, not the newest draft's</strong>
     * (TD §数据模型 V21): submitting a draft changes what is waiting, never what the skill says it
     * was last updated by. Publish and rollback move it, because they move the pointer. A missed
     * write here produces a perfectly well-formed card with an old fact in it, which is why this is
     * asserted at each of the four operations rather than at the end.
     */
    @Test
    void theCardsTimeAndDraftsFollowTheProjectionColumns() {
        // The server's own submission times are the expected values: two submissions can land in
        // the same second, so a test that compared the two card timestamps against each other would
        // be asserting something the wire does not promise.
        JsonNode submittedV1 = submit(first()).get("version");
        JsonNode submittedV2 = submit(second()).get("version");
        String atV1 = submittedV1.get("submitted_at").asText();
        String atV2 = submittedV2.get("submitted_at").asText();
        String d1 = rawDigest(submittedV1.get("digest").asText());
        String d2 = rawDigest(submittedV2.get("digest").asText());
        // Publish the first draft explicitly: two drafts are waiting, and "newest" is the second.
        publishDraft(submittedV1.get("draft_id").asText(), "1.0.0");

        // The row first, then the response: a projection that is wrong still serialises into a
        // well-formed card, so the response alone would not say which layer lied.
        Map<String, Object> row = projectionRow();
        assertThat(row.get("current_version_name")).isEqualTo("1.0.0");
        assertThat(row.get("current_version_id")).isEqualTo(versionId("1.0.0"));
        assertThat(row.get("current_digest")).isEqualTo(d1);
        assertThat(row.get("current_version_submitted_at")).isEqualTo(atV1);
        assertThat(row.get("draft_count")).isEqualTo(1);
        assertThat(row.get("newest_draft_version"))
                .as("a draft has no number until it is published (ADR 0055)")
                .isNull();
        assertThat(row.get("newest_draft_submitted_at")).isEqualTo(atV2);
        assertThat(row.get("when_to_use"))
                .as("extracted from the published version's frontmatter, not copied from the draft")
                .isEqualTo(whenToUse("1.0.0"));
        assertThat(row.get("title"))
                .as("the title is a projection of the pointer")
                .isEqualTo("PDF Tools");
        assertThat(row.get("description"))
                .as("the description is a projection of the pointer, like the title")
                .isEqualTo(descriptionOf("1.0.0"));

        JsonNode card = card();
        assertThat(card.get("updated_at").asText())
                .as("the card shows the version the pointer names")
                .isEqualTo(atV1);
        assertThat(card.get("drafts").asInt()).isEqualTo(1);

        // A draft submitted after the skill went live does not move the card's time.
        JsonNode submittedV3 = submit(third()).get("version");
        String atV3 = submittedV3.get("submitted_at").asText();
        String d3 = rawDigest(submittedV3.get("digest").asText());
        assertThat(atV3).isNotBlank();
        row = projectionRow();
        assertThat(row.get("draft_count")).isEqualTo(2);
        assertThat(row.get("newest_draft_version"))
                .as("a draft has no number until it is published (ADR 0055)")
                .isNull();
        assertThat(row.get("newest_draft_submitted_at")).isEqualTo(atV3);
        assertThat(row.get("newest_draft_digest")).isEqualTo(d3);
        assertThat(row.get("newest_title")).isEqualTo("PDF Tools v3");
        assertThat(row.get("newest_description")).isEqualTo(descriptionOf("3.0.0"));
        assertThat(row.get("current_version_submitted_at"))
                .as("the current-version projection does not follow a draft")
                .isEqualTo(atV1);
        card = card();
        assertThat(card.get("current").get("name").asText()).isEqualTo("1.0.0");
        assertThat(card.get("updated_at").asText())
                .as("the card shows the current version's submission time, not the newest draft's")
                .isEqualTo(atV1);
        assertThat(card.get("drafts").asInt()).isEqualTo(2);

        // Nor does discarding one.
        discard("3.0.0");
        row = projectionRow();
        assertThat(row.get("draft_count")).isEqualTo(1);
        assertThat(row.get("newest_draft_version"))
                .as("a draft has no number until it is published (ADR 0055)")
                .isNull();
        assertThat(row.get("newest_draft_submitted_at"))
                .as("a discard rewrites the newest-draft projection back to what is left")
                .isEqualTo(atV2);
        assertThat(row.get("newest_draft_digest")).isEqualTo(d2);
        assertThat(row.get("newest_title"))
                .as("the discarded version's title does not stay on the row")
                .isEqualTo("PDF Tools v2");
        assertThat(row.get("newest_description")).isEqualTo(descriptionOf("2.0.0"));
        card = card();
        assertThat(card.get("updated_at").asText()).isEqualTo(atV1);
        assertThat(card.get("drafts").asInt()).isEqualTo(1);

        // Publishing the remaining draft makes it the fact the card shows.
        publish("2.0.0");
        row = projectionRow();
        assertThat(row.get("current_version_name")).isEqualTo("2.0.0");
        assertThat(row.get("current_version_id")).isEqualTo(versionId("2.0.0"));
        assertThat(row.get("current_digest")).isEqualTo(d2);
        assertThat(row.get("current_version_submitted_at")).isEqualTo(atV2);
        assertThat(row.get("draft_count")).isEqualTo(0);
        assertThat(row.get("newest_draft_version")).isNull();
        assertThat(row.get("newest_draft_digest")).isNull();
        assertThat(row.get("newest_draft_submitted_at")).isNull();
        assertThat(row.get("newest_title")).as("the cleared projection includes the title").isNull();
        assertThat(row.get("newest_description"))
                .as("and its description")
                .isNull();
        assertThat(row.get("title")).isEqualTo("PDF Tools v2");
        assertThat(row.get("when_to_use")).isEqualTo(whenToUse("2.0.0"));
        assertThat(row.get("description")).isEqualTo(descriptionOf("2.0.0"));
        card = card();
        assertThat(card.get("current").get("name").asText()).isEqualTo("2.0.0");
        assertThat(card.get("title").asText()).isEqualTo("PDF Tools v2");
        assertThat(card.get("drafts").asInt()).isZero();
        assertThat(card.get("updated_at").asText()).isEqualTo(atV2);

        // A draft submitted after v2 went live does not move the pointer either.
        JsonNode submittedV4 = submit(fourth()).get("version");
        String atV4 = submittedV4.get("submitted_at").asText();
        String d4 = rawDigest(submittedV4.get("digest").asText());
        assertThat(atV4).isNotBlank();
        row = projectionRow();
        assertThat(row.get("current_version_name")).isEqualTo("2.0.0");
        assertThat(row.get("draft_count")).isEqualTo(1);
        assertThat(row.get("newest_draft_version"))
                .as("a draft has no number until it is published (ADR 0055)")
                .isNull();
        assertThat(row.get("newest_draft_submitted_at")).isEqualTo(atV4);
        assertThat(row.get("newest_draft_digest")).isEqualTo(d4);
        assertThat(row.get("newest_title")).isEqualTo("PDF Tools v4");
        assertThat(row.get("newest_description")).isEqualTo(descriptionOf("4.0.0"));

        // Rollback moves the pointer and the time back; the draft side is left alone.
        publish("1.0.0");
        row = projectionRow();
        assertThat(row.get("current_version_name")).isEqualTo("1.0.0");
        assertThat(row.get("current_version_id")).isEqualTo(versionId("1.0.0"));
        assertThat(row.get("current_digest")).isEqualTo(d1);
        assertThat(row.get("current_version_submitted_at")).isEqualTo(atV1);
        assertThat(row.get("draft_count"))
                .as("a rollback is not a publish of the draft: v4 is still waiting")
                .isEqualTo(1);
        assertThat(row.get("newest_draft_version"))
                .as("a draft has no number until it is published (ADR 0055)")
                .isNull();
        assertThat(row.get("newest_draft_submitted_at")).isEqualTo(atV4);
        assertThat(row.get("newest_draft_digest")).isEqualTo(d4);
        assertThat(row.get("newest_title")).isEqualTo("PDF Tools v4");
        assertThat(row.get("newest_description"))
                .as("the draft side is untouched by the rollback, description included")
                .isEqualTo(descriptionOf("4.0.0"));
        assertThat(row.get("when_to_use"))
                .as("a rollback moves the extracted field back with the pointer")
                .isEqualTo(whenToUse("1.0.0"));
        assertThat(row.get("title")).isEqualTo("PDF Tools");
        assertThat(row.get("description")).isEqualTo(descriptionOf("1.0.0"));
        card = card();
        assertThat(card.get("title").asText())
                .as("the card follows the pointer back too")
                .isEqualTo("PDF Tools");
        assertThat(card.get("description").asText()).isEqualTo(descriptionOf("1.0.0"));
        assertThat(card.get("current").get("name").asText()).isEqualTo("1.0.0");
        assertThat(card.get("updated_at").asText()).isEqualTo(atV1);
        assertThat(card.get("drafts").asInt()).isEqualTo(1);
    }

    /** The V21 projection columns of this test's one skill, read from the row itself. */
    private Map<String, Object> projectionRow() {
        return jdbc.sql("""
                SELECT current_version_id, current_version_name, current_digest,
                       current_version_submitted_at, draft_count, newest_draft_version,
                       newest_draft_digest, newest_draft_submitted_at, newest_title,
                       newest_description, when_to_use, s.title, s.description
                FROM skill s
                JOIN namespace n ON n.id = s.namespace_id
                WHERE n.slug = :slug AND s.name = 'pdf-tools'
                """)
                .param("slug", username)
                .query()
                .singleRow();
    }

    /** The wire spells a digest `sha256:…`; the row holds the hex alone. */
    private static String rawDigest(String wire) {
        return wire.startsWith("sha256:") ? wire.substring("sha256:".length()) : wire;
    }

    /** The row id of one of this skill's versions, by the name it declared. */
    private String versionId(String name) {
        return jdbc.sql("""
                SELECT v.id
                FROM skill_version v
                JOIN skill s ON s.id = v.skill_id
                JOIN namespace n ON n.id = s.namespace_id
                WHERE n.slug = :slug AND s.name = 'pdf-tools' AND v.version = :name
                """)
                .param("slug", username)
                .param("name", name)
                .query(String.class)
                .single();
    }

    private JsonNode card() {
        return body(webGet(BASE)).get("skills").get(0);
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
        publish("1.0.0");
        submit(second());
        discard("2.0.0");

        JsonNode detail = body(webGet(BASE + "/" + username + "/pdf-tools"));

        assertThat(detail.get("versions")).hasSize(2);
        JsonNode discarded = detail.get("versions").get(0);
        assertThat(discarded.get("name").isNull())
                .as("a draft was discarded before it ever had a number")
                .isTrue();
        assertThat(discarded.get("state").asText())
                .as("a discarded version is shown as discarded rather than made to look like it "
                        + "never existed")
                .isEqualTo("discarded");
        assertThat(discarded.get("state_at").asText()).isNotBlank();

        JsonNode live = detail.get("versions").get(1);
        assertThat(live.get("name").asText()).isEqualTo("1.0.0");
        assertThat(live.get("state").asText()).isEqualTo("published");
        assertThat(live.get("is_current").asBoolean()).isTrue();

        assertThat(detail.get("version").get("name").asText())
                .as("the bare address follows the pointer, and the pointer still names 1.0.0")
                .isEqualTo("1.0.0");
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
        publish("1.0.0");

        JsonNode body = replay(first());

        assertThat(body.get("created").asBoolean()).isFalse();
        assertThat(body.get("version").get("name").asText()).isEqualTo("1.0.0");
        assertThat(body.get("version").get("state").asText()).isEqualTo("published");
    }

    /** The same for a version that was thrown away — the row still holds the digest. */
    @Test
    void aReplayOfDiscardedContentSaysSoToo() {
        submit(first());
        discard("1.0.0");

        JsonNode body = replay(first());

        assertThat(body.get("version").get("name").isNull())
                .as("nothing new was written: the discarded row still holds that content")
                .isTrue();
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

        List<HttpResponse<String>> outcomes = concurrently(8, "publish", "1.0.0");
        assertThat(outcomes).extracting(HttpResponse::statusCode)
                .as("a double click is not a discard: %s", outcomes.stream()
                        .map(response -> response.statusCode() + " " + response.body()).toList())
                .containsOnly(200);

        assertThat(body(webGet(BASE + "/" + username + "/pdf-tools")).get("version").get("name").asText())
                .as("and the version it was about is the live one")
                .isEqualTo("1.0.0");
    }

    /**
     * The same race one operation over, and the same misreading.
     *
     * <p>`markDiscarded`'s guard is `state = 'draft'`, and zero rows updated means the version stopped
     * being a draft between the read and the update — which a concurrent discard of that same version
     * does just as readily as a concurrent publish. Reading only the zero told a double click on 丢弃
     * that its version had just been *published*, which is false and alarming both. That is the claim
     * this test exists to protect, and it is asserted below on every response rather than on a count.
     *
     * <p><strong>Both a 200 and a 400 are correct answers here, and demanding one of them would be
     * asserting a scheduling.</strong> A request's outcome is decided by where its own read of the
     * version lands relative to the winner's commit: a reader that saw `draft` and then lost the
     * guarded update gets a success, because the state it asked for is the state there is — while one
     * whose read already saw `discarded` is a repeat, and a repeat is a 400
     * ({@code discardingIsOneWayAndOnlyADraftCanBeDiscarded} pins that deliberately: the discard is
     * one-way, so it does not repeat). Nothing orders those eight reads, so a run in which all eight
     * land inside the window is luck. This test used to require it, and failed under load.
     */
    @Test
    void concurrentDiscardsOfTheSameVersionAllSucceed() throws Exception {
        submit(first());

        List<HttpResponse<String>> responses = concurrently(8, "discard", "1.0.0");

        // Never a 5xx, and never a status that means something else entirely. `isSubsetOf` rather
        // than `containsOnly`: the latter demands that every listed value be present, and a run in
        // which all eight land inside the window has no 400 at all.
        assertThat(responses).extracting(HttpResponse::statusCode).isSubsetOf(200, 400);
        assertThat(responses).extracting(HttpResponse::statusCode)
                .as("somebody won the race, so this is not eight refusals")
                .contains(200);
        // The refusal may only be the true one. This is the original bug, and it is what a response
        // body can be checked for regardless of which side of the window a request landed on.
        assertThat(responses)
                .filteredOn(response -> response.statusCode() == 400)
                .allSatisfy(response -> assertThat(JSON.readTree(response.body())
                        .get("error").get("message").asText())
                        .as("a lost discard is not a publish, and no answer may say it was")
                        .contains("only a draft can be discarded")
                        .doesNotContain("published while being discarded"));

        // And through all of it, the version is discarded.
        assertThat(body(webGet(BASE + "/" + username + "/pdf-tools")).get("versions").get(0)
                .get("state").asText()).isEqualTo("discarded");
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
        discard("1.0.0");

        JsonNode detail = body(webGet(BASE + "/" + username + "/pdf-tools"));

        assertThat(detail.get("version").get("name").isNull())
                .as("discarded before it was ever numbered")
                .isTrue();
        assertThat(detail.get("version").get("state").asText()).isEqualTo("discarded");

        JsonNode listed = body(webGet(BASE)).get("skills").get(0);
        assertThat(listed.get("current").isNull()).as("nothing is live").isTrue();
        assertThat(listed.get("updated_at").isNull())
                .as("and there is no submission time left to report")
                .isTrue();
    }

    @Test
    void aDraftIsReadableThroughItsOwnAddress() {
        submit(first());
        publish("1.0.0");
        submit(second());

        String draftPin = newestDraftPin();
        JsonNode detail = body(webGet(BASE + "/" + username + "/pdf-tools@" + draftPin.replace(":", "%3A")));

        assertThat(detail.get("version").get("name").isNull())
                .as("a draft has no number")
                .isTrue();
        assertThat(detail.get("version").get("state").asText()).isEqualTo("draft");
        assertThat(detail.get("version").get("is_current").asBoolean()).isFalse();
        assertThat(detail.get("version").get("submitted_at").asText()).isNotBlank();
        assertThat(detail.get("title").asText())
                .as("a pinned read describes the version it pinned, not the one that is live")
                .isEqualTo("PDF Tools v2");

        String bodyUri = detail.get("resources").get("body").asText();
        assertThat(bodyUri).isEqualTo(BASE + "/" + username + "/pdf-tools@" + draftPin + "/body");
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
        publish("1.0.0");
        submit(second());

        String draftPin = newestDraftPin();
        assertThat(webGet(BASE + "/" + username + "/pdf-tools@" + draftPin.replace(":", "%3A")
                        + "/files/references/notes.md").body())
                .as("a draft's reference file is readable here")
                .isEqualTo("unchanged\n");
        assertThat(webGet(BASE + "/" + username + "/pdf-tools@" + draftPin.replace(":", "%3A")
                        + "/files/SKILL.md").body())
                .as("and so is the body, by the same route")
                .contains("第二版");

        HttpResponse<String> missing =
                webGet(BASE + "/" + username + "/pdf-tools@" + draftPin.replace(":", "%3A") + "/files/nope.md");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(body(missing).get("error").get("code").asText())
                .as("a version that resolved without listing this relpath is its own code — "
                        + "§4.1 keeps the two nothings apart")
                .isEqualTo("file_not_found");

        assertThat(webGet(BASE + "/" + username + "/pdf-tools@9.9.9/files/SKILL.md").statusCode())
                .as("while a version that does not exist is the address's 404")
                .isEqualTo(404);
    }

    @Test
    void beforeAnythingIsPublishedTheBaselineIsNothing() {
        submit(first());

        JsonNode diff = body(webGet(BASE + "/" + username + "/pdf-tools/diff"));

        assertThat(diff.get("from").isNull()).as("no baseline rather than a placeholder").isTrue();
        assertThat(diff.get("to").asText())
                .as("the diff labels are address suffixes; an unpublished draft is addressed by its"
                        + " row id, with no state prefix")
                .isEqualTo(newestDraftPin());
        assertThat(diff.get("truncated").asBoolean()).isFalse();
        assertThat(diff.get("files")).extracting(file -> file.get("status").asText())
                .as("with nothing to compare against, every file of the version is new — which is "
                        + "also the honest answer to 'what would publishing this add'")
                .containsOnly("added");
    }

    @Test
    void theDiffOfADraftAgainstLiveIsWhatPublishingItWouldChange() {
        submit(first());
        publish("1.0.0");
        submit(second());

        JsonNode diff = body(webGet(BASE + "/" + username + "/pdf-tools/diff?to="
                + newestDraftPath()));

        assertThat(diff.get("from").asText())
                .as("omitting from compares against what is live, which is the question a person "
                        + "opening a draft has")
                .isEqualTo("1.0.0");
        assertThat(diff.get("to").asText()).isEqualTo(newestDraftPin());

        JsonNode skill = fileNode(diff, "SKILL.md");
        assertThat(skill.get("status").asText()).isEqualTo("modified");
        assertThat(skill.get("binary").asBoolean()).isFalse();
        assertThat(skill.get("added").asInt())
                .as("five lines differ, not one: the marker in the body, the title, the declared "
                        + "version, the description and `when_to_use` — a second version of one skill "
                        + "has to declare a new name (ADR 0033), and the other four are what the "
                        + "projection test below reads off the pointer")
                .isEqualTo(5);
        assertThat(skill.get("removed").asInt()).isEqualTo(5);

        // Read as a diff rather than by index: the marker is one line of a file whose frontmatter
        // is context, so where in the hunk it sits is the algorithm's business and not this test's.
        List<String> lines = new ArrayList<>();
        skill.get("hunks").get(0).get("lines").forEach(line -> lines.add(line.asText()));
        assertThat(lines)
                .as("the marker changed, and version, title, description and when_to_use with it")
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
        publish("1.0.0");
        submit(second());
        publish("2.0.0");
        submit(third());

        String thirdPin = newestDraftPin();
        JsonNode diff = body(webGet(BASE + "/" + username + "/pdf-tools/diff?from=1.0.0&to="
                + thirdPin));

        assertThat(diff.get("from").asText()).isEqualTo("1.0.0");
        assertThat(diff.get("to").asText()).isEqualTo(newestDraftPin());
        assertThat(diff.get("files")).extracting(file -> file.get("relpath").asText())
                .as("the version in between is not part of this comparison")
                .containsExactly("SKILL.md");
    }

    @Test
    void aVersionInTheAddressIsTheDefaultTarget() {
        submit(first());
        publish("1.0.0");
        submit(second());

        assertThat(body(webGet(BASE + "/" + username + "/pdf-tools@" + newestDraftPath()
                + "/diff")).get("to").asText())
                .as("the page's own address is enough to ask what publishing this version would "
                        + "change; the query only exists to override it")
                .isEqualTo(newestDraftPin());
    }

    @Test
    void discardingIsOneWayAndOnlyADraftCanBeDiscarded() {
        submit(first());
        publish("1.0.0");
        submit(second());

        String draftPin = newestDraftPin();
        assertThat(webPost(BASE + "/" + username + "/pdf-tools/discard",
                json(Map.of("version", draftPin))).statusCode()).isEqualTo(200);

        HttpResponse<String> republished = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("version", draftPin, "release_name", "2.0.0")));
        assertThat(republished.statusCode())
                .as("a version the author threw away is not one they can later put live by name")
                .isEqualTo(400);
        assertThat(body(republished).get("error").get("code").asText()).isEqualTo("invalid_request");
        assertThat(body(republished).get("error").get("message").asText()).contains("discarded");

        HttpResponse<String> discardedAgain = webPost(BASE + "/" + username + "/pdf-tools/discard",
                json(Map.of("version", draftPin)));
        assertThat(discardedAgain.statusCode())
                .as("the same discard reached twice is a success, so a retry is safe (TD discard)")
                .isEqualTo(200);
        assertThat(body(discardedAgain).get("state").asText()).isEqualTo("discarded");

        assertThat(body(webGet(BASE + "/" + username + "/pdf-tools")).get("version").get("name").asText())
                .as("through all of that the live version never moved")
                .isEqualTo("1.0.0");
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
        publish("1.0.0");
        String firstLiveAt = body(webGet(BASE + "/" + username + "/pdf-tools@1.0.0"))
                .get("version").get("state_at").asText();

        submit(second());
        publish("2.0.0");
        assertThat(body(webGet(BASE + "/" + username + "/pdf-tools")).get("version").get("name").asText())
                .isEqualTo("2.0.0");

        HttpResponse<String> rolledBack = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("version", "1.0.0")));

        assertThat(rolledBack.statusCode()).as("rollback: %s", rolledBack.body()).isEqualTo(200);
        assertThat(body(rolledBack).get("changed").asBoolean()).isTrue();
        assertThat(body(rolledBack).get("live_at").asText())
                .as("when that version first went live, not when it was asked for again")
                .isEqualTo(firstLiveAt);

        JsonNode detail = body(webGet(BASE + "/" + username + "/pdf-tools"));
        assertThat(detail.get("version").get("name").asText())
                .as("the bare address follows the pointer, which has moved backwards")
                .isEqualTo("1.0.0");
        assertThat(detail.get("versions"))
                .as("and the version it moved off is still published, not reopened as a draft")
                .extracting(version -> version.get("state").asText())
                .containsOnly("published");

        assertThat(get(address(), token).statusCode())
                .as("consumers get the older version now, which is what rolling back means")
                .isEqualTo(200);
        assertThat(webGet(BASE + "/" + username + "/pdf-tools/body").body()).contains("第一版");
        assertThat(get(address() + "@2.0.0", token).statusCode())
                .as("and the version that was current a moment ago is still addressable by its name")
                .isEqualTo(200);
    }

    @Test
    void publishingAVersionThatIsAlreadyLiveWritesNothing() {
        submit(first());
        publish("1.0.0");

        HttpResponse<String> again = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("version", "1.0.0")));

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(body(again).get("changed").asBoolean())
                .as("the state that was asked for, reached twice")
                .isFalse();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'publish'"))
                .as("a replay changes nothing, so it does not write a second audit row")
                .isEqualTo(1);
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

        assertThat(webGet(BASE + "/" + username + "/pdf-tools@9.9.9").statusCode()).isEqualTo(404);
        assertThat(webGet(BASE + "/" + username + "/pdf-tools/diff?from=9.9.9").statusCode())
                .as("an invented baseline is not a comparison against nothing — it is a miss")
                .isEqualTo(404);
        assertThat(webGet(BASE + "/" + username + "/pdf-tools/diff?to=9.9.9").statusCode())
                .isEqualTo(404);
    }

    /**
     * A note can be cleared, and the answer says there is none.
     *
     * <p>The column is {@code NOT NULL}; clearing stores the empty string, and both the write's echo
     * and every subsequent read present that as {@code null}. Before this test, writing {@code ""}
     * sent SQL NULL into the column and the request failed on a constraint.
     */
    @Test
    void aDraftNoteCanBeSetFoldedAndCleared() {
        submit(first());
        String notePath = BASE + "/" + username + "/pdf-tools/versions/" + newestDraftPath() + "/note";

        HttpResponse<String> set = browser.patch(uri(notePath), json(Map.of("note", "  补了按页  ")));
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
        assertThat(body(set).get("note").asText())
                .as("the stored value, whitespace folded, not the request's bytes")
                .isEqualTo("补了按页");

        HttpResponse<String> cleared = browser.patch(uri(notePath), json(Map.of("note", "")));
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        assertThat(body(cleared).get("note").isNull())
                .as("an empty note is absent on the wire").isTrue();

        JsonNode versions = body(webGet(BASE + "/" + username + "/pdf-tools")).get("versions");
        assertThat(versions.get(0).get("note").isNull())
                .as("and the list reads the same nothing").isTrue();
    }

    /** A publish body carrying a note is refused rather than accepted with the field dropped. */
    @Test
    void publishingWithANoteIsRefusedRatherThanIgnored() {
        submit(first());

        HttpResponse<String> response = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("version", newestDraftPin(), "release_name", "1.0.0",
                        "note", "late addition")));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo("invalid_request");
        JsonNode after = body(webGet(BASE + "/" + username + "/pdf-tools")).get("version");
        assertThat(after.get("state").asText())
                .as("and nothing was published: the only version is still a draft").isEqualTo("draft");
        assertThat(after.get("name").isNull()).isTrue();
    }

    /**
     * A taken-down version goes back into service through restore, not publish.
     *
     * <p>Letting publish fall through for a revoked row would move the pointer onto a version the
     * consumption plane filters out, and every bare address of the skill would answer 500 afterwards.
     */
    @Test
    void aTakenDownVersionCannotBePublishedBackIntoService() {
        submit(first());
        publish("1.0.0");
        submit(second());
        publish("2.0.0");

        assertThat(webPost(BASE + "/" + username + "/pdf-tools/versions/1.0.0/revoke", "{}")
                .statusCode()).isEqualTo(200);

        HttpResponse<String> republish = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("version", "1.0.0")));
        assertThat(republish.statusCode()).isEqualTo(400);
        assertThat(body(republish).get("error").get("code").asText())
                .isEqualTo("invalid_version_state");
        // A bare read resolves the pointer, and a pointer on a revoked row would be a 500 here.
        assertThat(webGet(BASE + "/" + username + "/pdf-tools").statusCode())
                .as("the pointer never moved onto the revoked row").isEqualTo(200);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'version_revoke'"))
                .isEqualTo(1);

        // Restoring is the way back, and it leaves its own trace.
        assertThat(webPost(BASE + "/" + username + "/pdf-tools/versions/1.0.0/restore", "{}")
                .statusCode()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'version_restore'"))
                .isEqualTo(1);
    }

    /**
     * Taking the whole skill offline and back: consumer reads answer 503, the pointer does not move,
     * and repeating either call is a success rather than an error or a second audit row.
     */
    @Test
    void takingASkillOfflineAndBackIsIdempotentAndKeepsServingAfterwards() {
        submit(first());
        publish("1.0.0");

        assertThat(webPost(BASE + "/" + username + "/pdf-tools/offline", "").statusCode())
                .isEqualTo(200);
        assertThat(webPost(BASE + "/" + username + "/pdf-tools/offline", "").statusCode())
                .as("the same switch reached twice is still a success (TD §skill 级服务开关)")
                .isEqualTo(200);
        assertThat(send(request(address(), token).build()).statusCode())
                .as("every consumer read is 503 while the skill is offline").isEqualTo(503);

        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'skill_offline'"))
                .as("repeating the switch does not write a second audit row")
                .isEqualTo(1);

        assertThat(webPost(BASE + "/" + username + "/pdf-tools/online", "").statusCode())
                .isEqualTo(200);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'skill_online'"))
                .isEqualTo(1);
        assertThat(send(request(address(), token).build()).statusCode())
                .as("the pointer still names 1.0.0, so serving resumes without re-publishing")
                .isEqualTo(200);
        assertThat(body(webGet(BASE + "/" + username + "/pdf-tools")).get("version").get("name")
                .asText()).isEqualTo("1.0.0");
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
    private List<HttpResponse<String>> concurrently(int times, String action, String suffix)
            throws Exception {
        String csrf = csrfToken();
        String cookie = "SKILLMASTER_SESSION=" + cookieValue("SKILLMASTER_SESSION")
                + "; " + Browser.CSRF_COOKIE + "=" + csrf;
        // Since v6 a draft is addressed by its id; the number it is being raced towards travels as
        // `release_name` for a publish.
        String pin = "publish".equals(action) ? newestDraftPin() : newestDraftPin();
        String payload = "publish".equals(action)
                ? json(Map.of("version", pin, "release_name", suffix))
                : json(Map.of("version", pin));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(times)) {
            List<Future<HttpResponse<String>>> futures = IntStream.range(0, times)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return send(HttpRequest
                                .newBuilder(uri(BASE + "/" + username + "/pdf-tools/" + action))
                                .header(HttpHeaders.COOKIE, cookie)
                                .header(Browser.CSRF_HEADER, csrf)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .POST(HttpRequest.BodyPublishers.ofString(payload))
                                .build());
                    }))
                    .toList();
            start.countDown();

            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
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

    /**
     * Publishes the newest draft, assigning the number the caller names.
     *
     * <p>Since v6 a draft has no number until publish, so the pin is its bare row id and the number
     * travels as {@code release_name} (ADR 0055). The helper resolves the draft from the
     * author page rather than from a response the test happened to keep.
     */
    private void publish(String releaseName) {
        JsonNode versions = body(webGet(BASE + "/" + username + "/pdf-tools")).get("versions");
        for (JsonNode version : versions) {
            // An already-published version with this number is a rollback, not a publish of a draft.
            if (releaseName.equals(version.get("name").asText())
                    && "published".equals(version.get("state").asText())) {
                HttpResponse<String> rolled = webPost(BASE + "/" + username + "/pdf-tools/publish",
                        json(Map.of("version", releaseName)));
                assertThat(rolled.statusCode()).as("rollback failed: %s", rolled.body())
                        .isEqualTo(200);
                return;
            }
        }
        HttpResponse<String> published = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("version", newestDraftPin(), "release_name", releaseName)));
        assertThat(published.statusCode()).as("publish failed: %s", published.body()).isEqualTo(200);
    }

    /** The same pin, encoded for a path segment — the colon is not left bare in a URL. */
    private void publishDraft(String draftId, String releaseName) {
        HttpResponse<String> published = webPost(BASE + "/" + username + "/pdf-tools/publish",
                json(Map.of("version", draftId, "release_name", releaseName)));
        assertThat(published.statusCode()).as("publish failed: %s", published.body()).isEqualTo(200);
    }

    private String newestDraftPath() {
        return newestDraftPin();
    }

    private String newestDraftPin() {
        JsonNode versions = body(webGet(BASE + "/" + username + "/pdf-tools")).get("versions");
        for (JsonNode version : versions) {
            if ("draft".equals(version.get("state").asText())) {
                return version.get("code").asText();
            }
        }
        throw new AssertionError("there is no draft to publish");
    }

    private void discard(String suffix) {
        // A published row is addressed by its number; a draft has none, so it is addressed by id.
        String pin = suffix;
        JsonNode versions = body(webGet(BASE + "/" + username + "/pdf-tools")).get("versions");
        for (JsonNode version : versions) {
            if ("draft".equals(version.get("state").asText())) {
                // The list is newest first, so the first draft is the one the caller means.
                pin = version.get("draft_id").asText();
                break;
            }
        }
        HttpResponse<String> discarded = webPost(BASE + "/" + username + "/pdf-tools/discard",
                json(Map.of("version", pin)));
        assertThat(discarded.statusCode()).as("discard failed: %s", discarded.body()).isEqualTo(200);
    }

    private static byte[] first() {
        return zip("1.0.0", "第一版", "PDF Tools");
    }

    private static byte[] second() {
        return zip("2.0.0", "第二版", "PDF Tools v2");
    }

    private static byte[] third() {
        return zip("3.0.0", "第三版", "PDF Tools v3");
    }

    private static byte[] fourth() {
        return zip("4.0.0", "第四版", "PDF Tools v4");
    }

    /** The `when_to_use` each fixture declares, with the version it belongs to. */
    private static String whenToUse(String version) {
        return switch (version) {
            case "1.0.0" -> "第一版用法";
            case "2.0.0" -> "第二版用法";
            default -> "第三版用法";
        };
    }

    /** The description each fixture declares — it moves with the pointer, like the title. */
    private static String descriptionOf(String version) {
        return "a skill for the author-plane tests at " + version;
    }

    /**
     * One skill, wrapped in a directory named after it, with a marker in its body and one reference
     * file that never changes — so a comparison has one modified file and one that is not in the
     * answer at all. Each of these three carries its own declared version name, because a second
     * submission of one skill has to be a new version (ADR 0033): the same name with different
     * content is refused.
     *
     * @param version the semver to declare in the frontmatter, echoed back on the wire
     * @param title   the display title, which a re-submission of the same skill may change
     */
    private static byte[] zip(String version, String marker, String title) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pdf-tools/SKILL.md",
                "---\nname: pdf-tools\ntitle: " + title + "\n"
                        + "version: \"" + version + "\"\n"
                        + "description: " + descriptionOf(version) + "\n"
                        + "metadata:\n  when_to_use: " + whenToUse(version) + "\n"
                        + "---\n" + marker + "\n");
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
