package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.CursorCodec;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T7 in test-plan.md, and §3.4's two hard requirements: the caller sees only their own skills, and
 * a two-character Chinese query finds something.
 *
 * <p>The second is the reason this slice exists at all. The archived baseline used SQLite FTS5,
 * whose tokeniser produces no tokens at all for a two-character Chinese string — measured, not
 * assumed — so {@code MATCH} returned zero rows for the queries an actual user types. That finding
 * is what moved the store to PostgreSQL (ADR 0010). {@code LIKE} has no tokeniser and therefore no
 * such hole, which is why P0 ships it and treats {@code pg_bigm} as an index optimisation to be
 * added later rather than a prerequisite.
 *
 * <p>Skills are inserted directly: what is under test is the query and the ordering, and going
 * through publish would let a publish bug masquerade as a search bug.
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillSearchIT extends AbstractAccountIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String DEMO_NAMESPACE_ID = "01M3HTGC79VYJGM8BFXHX2QYNH";
    private static final String DEMO_USER_ID = "01M3HTG7GCCVBGRPAFFSVSF12W";
    private static final String OTHER_NAMESPACE_ID = "01M3HTGC79CHKDB4Q0T2JMRCWV";
    private static final String OTHER_USER_ID = "01M3HTG7GDQ71Q28CCP7J0HM8T";

    @Test
    void aTwoCharacterChineseQueryFindsTheSkill() {
        // The exact shape that made SQLite FTS5 unusable: two Han characters, which its tokeniser
        // emits nothing for. If this test ever starts failing, the search backend has been changed
        // to something with a tokeniser again.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-tools", "PDF 工具", "提取与合并 PDF");
        // A row that must not match. Without one, "found the skill" and "returned everything"
        // are the same observation — which is how a missing WHERE clause went unnoticed here once.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "unrelated", "Unrelated", "nothing in common");

        JsonNode skills = search("?q=%E6%8F%90%E5%8F%96&scope=public");

        assertThat(names(skills)).containsExactly("pdf-tools");
    }

    @Test
    void aCardIsL1FieldsAndNothingElse() {
        // §4.2's card, pinned field by field. **Five fields, and the list is the contract.** `title`,
        // `visibility` and `updated_at` were here and were removed: nothing read them, and the card is
        // the layer loaded for *every* skill, so what it carries is a budget. The title was the one
        // that cost something — it is not a field of the Agent Skills specification, it is not one
        // Claude Code recognises, and it falls back to the name, so the listing printed a skill's name
        // twice. `version` went last, and for the same reason: it was there so a single search was
        // enough to pin, and since ADR 0035 the client resolves the version and remembers it itself,
        // so a card that carried it would be carrying something no one reads.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-tools", "PDF 工具", "提取与合并 PDF");

        JsonNode card = search("?q=pdf&scope=public").get(0);

        // v14's card: L1 plus what both listings show. `source` is null for a discovery result
        // nobody added; `my_tags` is empty until the entry is effective.
        assertThat(card.propertyNames()).containsExactlyInAnyOrder("id", "name", "description",
                "when_to_use", "namespace", "source", "state", "version", "updated_at",
                "deleted_at", "category", "public_tags", "my_tags");
        // Absent is null and not the empty string, and not a missing key: a client cannot tell a
        // field that was not sent from one whose value is empty, and "this author declared none" is a
        // fact about the skill.
        assertThat(card.get("when_to_use").isNull())
                .as("this fixture's SKILL.md declares no when_to_use")
                .isTrue();
    }

    @Test
    void aCardsWhenToUseIsTheOneTheAuthorDeclared() {
        // The half of the L1 that is a judgement rather than a summary. It is read from the version
        // the card points at, which is the same row `title` and `description` were projected from —
        // so a card can never pair one version's description with another's when_to_use.
        insertSkillWithFrontmatter(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-tools", "PDF 工具",
                "提取与合并 PDF", "{\"metadata\": {\"when_to_use\": \"要动 PDF 的时候用\"}}");
        insertSkillWithFrontmatter(DEMO_NAMESPACE_ID, DEMO_USER_ID, "sheets", "Sheets",
                "表格", "{}");

        assertThat(search("?q=pdf&scope=public").get(0).get("when_to_use").asText())
                .isEqualTo("要动 PDF 的时候用");
        assertThat(search("?q=sheets&scope=public").get(0).get("when_to_use").isNull())
                .as("an author who declared none gets null, not a sentence we invented")
                .isTrue();
    }

    @Test
    void aQueryThatMatchesNothingReturnsNothing() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-tools", "PDF 工具", "提取与合并 PDF");

        assertThat(search("?q=nothingresembles&scope=public")).isEmpty();
    }

    @Test
    void aQueryMatchesTheDescriptionAsWellAsTheName() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-tools", "PDF tools",
                "提取与合并 PDF");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "spreadsheets", "Sheets",
                "a merge of columns");

        assertThat(names(search("?q=merge&scope=public")))
                .as("a hit anywhere in the three indexed fields is a hit")
                .containsExactly("spreadsheets");
    }

    @Test
    void anotherUsersSkillNeverAppears() {
        // §3.4: this predicate is the one whose omission leaks every other user's private skills,
        // and it is why the filter is in the query rather than applied afterwards.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "mine", "Mine", "a shared keyword here");
        insertSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "theirs", "Theirs", "a shared keyword here");

        // v14: the library is where "mine" is answered; the public plane intentionally contains
        // every public skill, including other people's.
        assertThat(search("?q=keyword&scope=library")).hasSize(1);
        assertThat(search("?scope=library")).hasSize(1);
        assertThat(search("?scope=library").get(0).get("name").asText()).isEqualTo("mine");
    }

    @Test
    void namingSomeoneElsesNamespaceNarrowsToNothingRatherThanToTheirSkills() {
        // `namespace` is documented as a filter within what the caller may see, never a bypass. The
        // way it could have been a bypass is if it *replaced* the ownership predicate instead of
        // refining it.
        //
        // A skill in the caller's own namespace is inserted as well, because "empty" on its own
        // cannot tell the filter from a parameter that is silently ignored: with only the other
        // user's skill present, every one of these assertions would hold just as well if
        // `namespace` did nothing at all.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "mine", "Mine", "shared keyword");
        insertSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "theirs", "Theirs", "shared keyword");

        assertThat(search("?namespace=other&scope=library")).isEmpty();
        assertThat(search("?namespace=other&q=keyword&scope=library")).isEmpty();
        assertThat(search("?namespace=demo&scope=library")).hasSize(1);
        assertThat(search("?namespace=demo&scope=library").get(0).get("name").asText())
                .isEqualTo("mine");
    }

    @Test
    void severalNamespacesInOneRequestAreTheUnionOfWhatTheCallerMaySee() {
        // The reason the parameter repeats: a caller reads in more than one namespace — their own,
        // plus every namespace a skill was shared with them from — and "mine together with what the
        // other person shared" is one question rather than two listings to be stitched together.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "mine", "Mine", "shared keyword");
        grantToDemo(insertSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "theirs", "Theirs", "shared keyword"));

        assertThat(search("?namespace=demo&scope=library"))
                .extracting(n -> n.get("name").asText()).containsExactly("mine");
        assertThat(search("?namespace=other&scope=library"))
                .extracting(n -> n.get("name").asText()).containsExactly("theirs");
        assertThat(search("?namespace=demo&namespace=other&scope=library"))
                .as("both, ordered by the listing's own rule rather than by the parameter order")
                .extracting(n -> n.get("name").asText())
                .containsExactlyInAnyOrder("mine", "theirs");
        // And the unshared skill in that other namespace is still not there: repeating the parameter
        // is a narrower question, never a wider one.
        assertThat(search("?namespace=other&q=nothing-matches&scope=library")).isEmpty();
    }

    @Test
    void anUnreadableNamespaceAmongReadableOnesContributesNothing() {
        // **The rule for a set of slugs, and the single-slug case generalised rather than replaced.**
        // One name the caller cannot read removes nothing from the answer; it does not empty it. The
        // other rule — refuse the whole request when any member is unknown — would make
        // `--namespace mine --namespace typo` answer with nothing, which reads as "you have no
        // skills" rather than as "one of those names is wrong".
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "mine", "Mine", "shared keyword");
        insertSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "theirs", "Theirs", "shared keyword");

        assertThat(search("?namespace=demo&namespace=other&scope=library"))
                .as("demo survives, other contributes nothing")
                .hasSize(1);
        assertThat(search("?namespace=other&namespace=demo&scope=library")).hasSize(1);
        // Every name unknown is the same nothing the single-slug case produces, and not a 404: the
        // caller asked about a namespace, not about their right to it.
        assertThat(search("?namespace=nobody&namespace=also-nobody&scope=library")).isEmpty();
    }

    @Test
    void ranksANameHitAboveATitleHitAboveADescriptionHit() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "plain", "unrelated", "mentions widget once");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "plain2", "widget in title", "unrelated");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "widget-named", "unrelated", "unrelated");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "no-match", "unrelated", "unrelated");

        JsonNode skills = search("?q=widget&scope=public");

        assertThat(names(skills)).doesNotContain("no-match");
        assertThat(names(skills))
                .as("weights are name 100 / title 40 / description 20")
                .containsExactly("widget-named", "plain2", "plain");
    }

    @Test
    void aNewerSkillWinsATieOnTextRelevanceWithoutEverOutrankingAHigherField() {
        // The reason recency is a tie-breaker inside a tier rather than a term added to a score:
        // a sum containing the current time cannot be a keyset. Two skills that both hit only the
        // description tie on tier, and the newer one comes first.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "older", "x", "widget", "2020-01-01T00:00:00Z");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "newer", "x", "widget", "2026-01-01T00:00:00Z");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "widget-ancient", "x", "nothing",
                "1999-01-01T00:00:00Z");

        JsonNode skills = search("?q=widget&scope=public");

        assertThat(names(skills))
                .as("an ancient name hit still beats a fresh description hit")
                .containsExactly("widget-ancient", "newer", "older");
    }

    @Test
    void anEmptyQueryListsEverythingNewestFirst() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "old", "x", "y", "2020-01-01T00:00:00Z");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "new", "x", "y", "2026-01-01T00:00:00Z");

        assertThat(names(search("?scope=library"))).containsExactly("new", "old");
        assertThat(names(search("?scope=library&sort=recent")))
                .containsExactly("new", "old");
    }

    @Test
    void sortOrdersTheMatchesAndQDecidesWhichTheyAre() {
        // v14's library has two orderings and no relevance: `q` decides the set, `sort` the order.
        // Two rows that both match, with names and times that disagree, so each sort has a
        // different answer.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "alpha", "x", "mentions widget",
                "2020-01-01T00:00:00Z");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "zulu", "x", "mentions widget",
                "2026-01-01T00:00:00Z");

        assertThat(names(search("?q=widget&scope=library")))
                .as("by default the newest is first")
                .containsExactly("zulu", "alpha");
        assertThat(names(search("?q=widget&scope=library&sort=name")))
                .as("sort=name means what it says, even when recency would order it differently")
                .containsExactly("alpha", "zulu");
    }

    @Test
    void percentAndUnderscoreAreSearchedForRatherThanTreatedAsWildcards() {
        // Without escaping, `%` matches everything and `_` matches any character, so the caller's
        // text would be read as a pattern language. The value is still a bound parameter, so this
        // is about meaning rather than about injection.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "has-percent", "100% coverage", "y");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "has-underscore", "a_b naming", "y");

        assertThat(names(search("?q=%25&scope=public")))
                .as("a literal percent matches only the skill containing one")
                .containsExactly("has-percent");
        assertThat(names(search("?q=a_b&scope=public")))
                .as("a literal underscore does not match 'aXb'")
                .containsExactly("has-underscore");
    }

    @Test
    void pagesThroughTheWholeResultSetExactlyOnce() {
        // "No repeats and no skips" is the property keyset pagination exists for, and the only way
        // to check it is to walk every page and compare the union to the full list.
        List<String> inserted = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String name = "skill-" + i;
            inserted.add(name);
            // Deliberately heavy on ties: several rows share a tier and a timestamp, so the id
            // tiebreaker is what keeps the boundary from falling through them.
            insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, name, "x", "widget",
                    "2026-01-0" + (i % 3 + 1) + "T00:00:00Z");
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            String query = "?q=widget&scope=public&limit=3"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode body = JSON.readTree(get("/api/v1/skills" + query, token()).body());
            body.get("skills").forEach(card -> seen.add(card.get("name").asText()));
            cursor = body.get("next_cursor").isNull() ? null : body.get("next_cursor").asText();
            if (cursor == null) {
                break;
            }
        }

        assertThat(cursor).as("pagination ended rather than running out of attempts").isNull();
        assertThat(seen)
                .as("every skill exactly once, in one pass")
                .containsExactlyInAnyOrderElementsOf(inserted)
                .doesNotHaveDuplicates();
    }

    @Test
    void anExcludedAddressIsNotReturned() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "widget-a", "x", "widget");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "widget-b", "x", "widget");

        JsonNode kept = search("?q=widget&scope=public&exclude=demo/widget-a");

        assertThat(names(kept)).containsExactly("widget-b");
    }

    @Test
    void anExcludeThatNamesNothingIsIgnored() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "widget-a", "x", "widget");

        assertThat(names(search("?q=widget&scope=public&exclude=ghost/nope")))
                .as("excluding a row that would not have appeared is a no-op, not an error")
                .containsExactly("widget-a");
    }

    @Test
    void anEmptyExcludeIsIgnored() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "widget-a", "x", "widget");

        assertThat(names(search("?q=widget&scope=public&exclude=")))
                .as("a client that always emits the parameter names nothing")
                .containsExactly("widget-a");
    }

    @Test
    void aMalformedExcludeIsABadRequest() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "widget-a", "x", "widget");

        for (String bad : List.of("widget", "demo/widget/SKILL.md", "demo/widget@1.0.0")) {
            HttpResponse<String> response =
                    get("/api/v1/skills?q=widget&scope=public&exclude=" + bad, token());
            assertThat(response.statusCode()).as("exclude=%s", bad).isEqualTo(400);
            assertThat(response.body()).contains("exclude");
        }
    }

    @Test
    void moreThanFiftyExcludesIsABadRequest() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "widget-a", "x", "widget");
        StringBuilder query = new StringBuilder("?q=widget&scope=public");
        for (int i = 0; i < 51; i++) {
            query.append("&exclude=ghost/s").append(i);
        }

        assertThat(get("/api/v1/skills" + query, token()).statusCode()).isEqualTo(400);
    }

    @Test
    void oneAddressRepeatedFiftyOneTimesIsStillOneAddress() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "widget-a", "x", "widget");
        StringBuilder query = new StringBuilder("?q=widget&scope=public");
        for (int i = 0; i < 51; i++) {
            query.append("&exclude=demo/widget-a");
        }

        assertThat(get("/api/v1/skills" + query, token()).statusCode())
                .as("the ceiling bounds distinct addresses, not repetitions")
                .isEqualTo(200);
    }

    @Test
    void excludingDoesNotShrinkThePageOrRepeatRows() {
        // The property the widened fetch exists for: the page is as full as it would have been
        // without the exclusion, and walking with the same exclusion visits every kept row once.
        List<String> inserted = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String name = "skill-" + i;
            inserted.add(name);
            insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, name, "x", "widget",
                    "2026-01-0" + (i % 3 + 1) + "T00:00:00Z");
        }
        String excluded = "demo/skill-0";

        List<String> seen = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            String query = "?q=widget&scope=public&limit=3&exclude=" + excluded
                    + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode body = JSON.readTree(get("/api/v1/skills" + query, token()).body());
            pageSizes.add(body.get("skills").size());
            body.get("skills").forEach(card -> seen.add(card.get("name").asText()));
            cursor = body.get("next_cursor").isNull() ? null : body.get("next_cursor").asText();
            if (cursor == null) {
                break;
            }
        }

        assertThat(cursor).isNull();
        assertThat(pageSizes.get(0)).as("the first page is still full").isEqualTo(3);
        assertThat(seen)
                .as("every kept skill exactly once")
                .containsExactlyInAnyOrderElementsOf(
                        inserted.stream().filter(name -> !name.equals("skill-0")).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void aMalformedCursorIsRejectedRatherThanRestartingTheListing() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-tools", "x", "y");

        HttpResponse<String> response = get("/api/v1/skills?cursor=not-a-cursor", token());

        assertThat(response.statusCode())
                .as("silently starting over would look to a client like an infinite loop")
                .isEqualTo(400);
        assertThat(response.body()).contains("invalid_request");
    }

    @Test
    void aCursorWhoseKeyIsNotWhatItClaimsIsRejectedRatherThanFailingTheQuery() {
        // A cursor is opaque but not signed, so a hand-edited one reaches the query — where the
        // first key part is CAST(… AS integer). An uncastable value is a SQL error, and a SQL error
        // on a request path is a 500 for what §4.1 calls a cursor nobody can read.
        insertSkillsForPaging();
        String issued = JSON.readTree(
                        get("/api/v1/skills?q=widget&scope=public&limit=1", token()).body())
                .get("next_cursor").asText();
        String ordering = JSON.readTree(new String(Base64.getUrlDecoder().decode(issued),
                StandardCharsets.UTF_8)).get("o").asText();
        String tampered = CursorCodec.encode(ordering,
                List.of("abc", "2026-09-28T00:00:00Z", "01M3HTG7GCCVBGRPAFFSVSF12W"));

        HttpResponse<String> response =
                get("/api/v1/skills?q=widget&scope=public&limit=1&cursor=" + tampered, token());

        assertThat(response.statusCode()).as("body was: %s", response.body()).isEqualTo(400);
        assertThat(response.body()).contains("invalid_request");

        // The timestamp half of the same check, and the reason it is a round-trip rather than a
        // parse: the keyset compares updated_at as *text*, so a parseable-but-different spelling of
        // the same instant moves the page boundary without any error anywhere.
        String offsetForm = CursorCodec.encode(ordering,
                List.of("0", "2026-09-28T00:00:00+00:00", "01M3HTG7GCCVBGRPAFFSVSF12W"));

        assertThat(get("/api/v1/skills?q=widget&scope=public&limit=1&cursor=" + offsetForm,
                token()).statusCode())
                .as("the same instant written differently is not the text this ordering compares")
                .isEqualTo(400);

        // The score half, with a value that is a number to Java and not to PostgreSQL:
        // Integer.parseInt takes every Unicode decimal digit, the AS integer cast takes ASCII only.
        String arabicIndic = CursorCodec.encode(ordering,
                List.of("٣", "2026-09-28T00:00:00Z", "01M3HTG7GCCVBGRPAFFSVSF12W"));

        assertThat(get("/api/v1/skills?q=widget&scope=public&limit=1&cursor=" + arabicIndic,
                token()).statusCode())
                .as("an Arabic-Indic three parses in Java and not in the cast the cursor feeds")
                .isEqualTo(400);
    }

    @Test
    void aCursorFromADifferentOrderingIsRejected() {
        // The cursor carries scores computed under one ordering. Resuming it under another would
        // compare tiers that share no scale, producing a page of arbitrary rows with no error.
        insertSkillsForPaging();
        String cursor = JSON.readTree(
                        get("/api/v1/skills?q=widget&scope=public&limit=1", token()).body())
                .get("next_cursor").asText();

        // v14: the public plane has exactly one ordering, so `sort` is refused outright rather than
        // selecting a second one.
        assertThat(get("/api/v1/skills?q=widget&scope=public&sort=recent&limit=1&cursor=" + cursor,
                token()).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/skills?q=widget&scope=public&limit=1&cursor=" + cursor,
                token()).statusCode())
                .as("the same ordering it came from is accepted")
                .isEqualTo(200);

        // A retuned weighting has the same width and a different scale, so only the identity carried
        // in the cursor can reject it — and that is the case this assertion exists for.
        String retuned = CursorCodec.encode("relevance:1,2,3",
                List.of("0", "2026-09-28T00:00:00Z", "01M3HTG7GCCVBGRPAFFSVSF12W"));

        assertThat(get("/api/v1/skills?q=widget&scope=public&limit=1&cursor=" + retuned,
                token()).statusCode())
                .as("same width, different weights: the identity carried in the cursor is the only "
                        + "thing that can reject this")
                .isEqualTo(400);
    }

    @Test
    void theLimitDefaultsToTwentyAndIsCappedAtOneHundred() {
        // More rows than the ceiling, so the clamp is actually reached: a fixture below it makes
        // every assertion here hold identically with no clamp at all, which is how this test used
        // to pass while pinning nothing.
        for (int i = 0; i < 105; i++) {
            insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "skill-" + i, "x", "y");
        }

        assertThat(search("?scope=public&q=skill").size()).isEqualTo(20);
        assertThat(search("?scope=public&q=skill&limit=100").size()).isEqualTo(100);
        assertThat(search("?scope=public&q=skill&limit=101").size())
                .as("a larger request is clamped to the documented ceiling")
                .isEqualTo(100);
        assertThat(search("?scope=public&q=skill&limit=100000").size())
                .as("and a much larger one is clamped too, not merely refused")
                .isEqualTo(100);
        assertThat(get("/api/v1/skills?scope=public&q=skill&limit=0", token()).statusCode())
                .as("zero rows is a mistake, not a smaller answer")
                .isEqualTo(400);
    }

    @Test
    void anUnknownSortIsRejected() {
        assertThat(get("/api/v1/skills?sort=alphabetical", token()).statusCode()).isEqualTo(400);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private JsonNode search(String queryString) {
        HttpResponse<String> response = get("/api/v1/skills" + queryString, token());
        assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("skills");
    }

    private static List<String> names(JsonNode skills) {
        return skills.valueStream().map(card -> card.get("name").asText()).toList();
    }

    private void insertSkillsForPaging() {
        for (int i = 0; i < 3; i++) {
            insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "skill-" + i, "x", "widget");
        }
    }

    private String insertSkill(String namespaceId, String userId, String name, String title,
            String description) {
        return insertSkill(namespaceId, userId, name, title, description, "2026-09-28T00:00:00Z");
    }

    private String insertSkill(String namespaceId, String userId, String name, String title,
            String description, String updatedAt) {
        return insertSkillWithFrontmatter(namespaceId, userId, name, title, description, updatedAt,
                "{}");
    }

    /**
     * The same, with the version's frontmatter chosen by the test.
     *
     * <p>Its own entry point because `when_to_use` and the rest live in that column and nowhere else:
     * the listing reads it straight out of the version's JSON, so a fixture that hard-coded `{}`
     * could not exercise it at all.
     *
     * @return the skill's id, so a test can share it with somebody ({@link #grantToDemo})
     */
    private String insertSkillWithFrontmatter(String namespaceId, String userId, String name,
            String title, String description, String frontmatter) {
        return insertSkillWithFrontmatter(namespaceId, userId, name, title, description,
                "2026-09-28T00:00:00Z", frontmatter);
    }

    private String insertSkillWithFrontmatter(String namespaceId, String userId, String name,
            String title, String description, String updatedAt, String frontmatter) {
        String skillId = Ulid.generate();
        String versionId = Ulid.generate();
        String digest = sha256Hex((name + updatedAt).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        jdbc.sql("""
                INSERT INTO blob (sha256, size, created_at) VALUES (:sha, 0, :at)
                ON CONFLICT (sha256) DO NOTHING
                """).param("sha", digest).param("at", updatedAt).update();
        jdbc.sql("""
                INSERT INTO blob_content (sha256, bytes) VALUES (:sha, '')
                ON CONFLICT (sha256) DO NOTHING
                """).param("sha", digest).update();

        // The V21 projections are written by hand here: this fixture inserts rows directly, so
        // there is no recordPublish to compute them. `current_version_submitted_at` is what the
        // listing's "更新于" and its cursor read; `when_to_use` is what the card carries.
        // v14: the public scope is the one that keeps the relevance ordering, so the fixtures are
        // public. V19's CHECK requires a rung with `public`; `browse` is the higher of the two.
        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, public_rung, current_version_id, current_version_name,
                                   current_digest, current_version_submitted_at, when_to_use,
                                   created_by, created_at, updated_at)
                VALUES (:id, :namespace, :name, :title, :description, :frontmatter, 'public', 'browse',
                        :version, '1.0.0', :digest, :at,
                        CAST(:frontmatter AS jsonb) #>> '{metadata,when_to_use}',
                        :user, :at, :at)
                """)
                .param("id", skillId).param("namespace", namespaceId).param("name", name)
                .param("title", title).param("description", description).param("version", versionId)
                .param("digest", digest).param("frontmatter", frontmatter)
                .param("user", userId).param("at", updatedAt)
                .update();

        jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, version, digest, file_count,
                                           total_bytes, changelog, source, submitted_by, submitted_at,
                                           state, state_at, first_published_at, title, description, frontmatter)
                VALUES (:id, :skill, 1, '1.0.0', :digest, 0, 0, '', 'zip', :user, :at,
                        'published', :at, :at, :title, :description, :frontmatter)
                """)
                .param("id", versionId).param("skill", skillId).param("digest", digest)
                .param("title", title).param("description", description)
                .param("user", userId).param("at", updatedAt)
                .param("frontmatter", frontmatter)
                .update();
        return skillId;
    }

    /**
     * Shares a skill with the caller the tests act as, which is what makes a namespace other than
     * their own readable — and therefore worth filtering on.
     *
     * <p>Inserted directly rather than through the API because these tests are about the *listing*,
     * and the three sharing routes have their own suite. What is being set up here is a state, not a
     * behaviour.
     */
    private void grantToDemo(String skillId) {
        jdbc.sql("""
                INSERT INTO skill_grant (skill_id, grantee_id, role, granted_by, created_at)
                VALUES (:skill, :grantee, 'browse', :by, '2026-09-28T00:00:00Z')
                """)
                .param("skill", skillId).param("grantee", DEMO_USER_ID)
                .param("by", OTHER_USER_ID)
                .update();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE", e);
        }
    }
}
