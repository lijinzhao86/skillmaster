package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.CursorCodec;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.support.AbstractIT;
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
class SkillSearchIT extends AbstractIT {

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

        JsonNode skills = search("?q=%E6%8F%90%E5%8F%96");

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

        JsonNode card = search("?q=pdf").get(0);

        assertThat(card.propertyNames()).containsExactlyInAnyOrder("id", "name", "description",
                "when_to_use", "namespace");
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
                "提取与合并 PDF", "{\"when_to_use\": \"要动 PDF 的时候用\"}");
        insertSkillWithFrontmatter(DEMO_NAMESPACE_ID, DEMO_USER_ID, "sheets", "Sheets",
                "表格", "{}");

        assertThat(search("?q=pdf").get(0).get("when_to_use").asText())
                .isEqualTo("要动 PDF 的时候用");
        assertThat(search("?q=sheets").get(0).get("when_to_use").isNull())
                .as("an author who declared none gets null, not a sentence we invented")
                .isTrue();
    }

    @Test
    void aQueryThatMatchesNothingReturnsNothing() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-tools", "PDF 工具", "提取与合并 PDF");

        assertThat(search("?q=nothingresembles")).isEmpty();
    }

    @Test
    void aQueryMatchesTheDescriptionAsWellAsTheName() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-tools", "PDF tools",
                "提取与合并 PDF");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "spreadsheets", "Sheets",
                "a merge of columns");

        assertThat(names(search("?q=merge")))
                .as("a hit anywhere in the three indexed fields is a hit")
                .containsExactly("spreadsheets");
    }

    @Test
    void anotherUsersSkillNeverAppears() {
        // §3.4: this predicate is the one whose omission leaks every other user's private skills,
        // and it is why the filter is in the query rather than applied afterwards.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "mine", "Mine", "a shared keyword here");
        insertSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "theirs", "Theirs", "a shared keyword here");

        assertThat(search("?q=keyword")).hasSize(1);
        assertThat(search("")).hasSize(1);
        assertThat(search("").get(0).get("name").asText()).isEqualTo("mine");
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

        assertThat(search("?namespace=other")).isEmpty();
        assertThat(search("?namespace=other&q=keyword")).isEmpty();
        assertThat(search("?namespace=demo")).hasSize(1);
        assertThat(search("?namespace=demo").get(0).get("name").asText()).isEqualTo("mine");
    }

    @Test
    void severalNamespacesInOneRequestAreTheUnionOfWhatTheCallerMaySee() {
        // The reason the parameter repeats: a caller reads in more than one namespace — their own,
        // plus every namespace a skill was shared with them from — and "mine together with what the
        // other person shared" is one question rather than two listings to be stitched together.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "mine", "Mine", "shared keyword");
        grantToDemo(insertSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "theirs", "Theirs", "shared keyword"));

        assertThat(search("?namespace=demo")).extracting(n -> n.get("name").asText())
                .containsExactly("mine");
        assertThat(search("?namespace=other")).extracting(n -> n.get("name").asText())
                .containsExactly("theirs");
        assertThat(search("?namespace=demo&namespace=other"))
                .as("both, ordered by the listing's own rule rather than by the parameter order")
                .extracting(n -> n.get("name").asText())
                .containsExactlyInAnyOrder("mine", "theirs");
        // And the unshared skill in that other namespace is still not there: repeating the parameter
        // is a narrower question, never a wider one.
        assertThat(search("?namespace=other&q=nothing-matches")).isEmpty();
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

        assertThat(search("?namespace=demo&namespace=other"))
                .as("demo survives, other contributes nothing")
                .hasSize(1);
        assertThat(search("?namespace=other&namespace=demo")).hasSize(1);
        // Every name unknown is the same nothing the single-slug case produces, and not a 404: the
        // caller asked about a namespace, not about their right to it.
        assertThat(search("?namespace=nobody&namespace=also-nobody")).isEmpty();
    }

    @Test
    void ranksANameHitAboveATitleHitAboveADescriptionHit() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "plain", "unrelated", "mentions widget once");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "plain2", "widget in title", "unrelated");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "widget-named", "unrelated", "unrelated");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "no-match", "unrelated", "unrelated");

        JsonNode skills = search("?q=widget");

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

        JsonNode skills = search("?q=widget");

        assertThat(names(skills))
                .as("an ancient name hit still beats a fresh description hit")
                .containsExactly("widget-ancient", "newer", "older");
    }

    @Test
    void anEmptyQueryListsEverythingNewestFirst() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "old", "x", "y", "2020-01-01T00:00:00Z");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "new", "x", "y", "2026-01-01T00:00:00Z");

        assertThat(names(search(""))).containsExactly("new", "old");
        assertThat(names(search("?sort=recent"))).containsExactly("new", "old");
    }

    @Test
    void sortOrdersTheMatchesAndQDecidesWhichTheyAre() {
        // Two rows that both match, whose relevance and recency disagree: the name hit is old, the
        // description hit is new. `q` decides the set, `sort` decides the order — which is the
        // distinction the previous version of this test got wrong, by pairing a query with a row
        // that did not contain it.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "widget-ancient", "x", "y",
                "2020-01-01T00:00:00Z");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "recent", "x", "mentions widget",
                "2026-01-01T00:00:00Z");

        assertThat(names(search("?q=widget")))
                .as("by default the name hit outranks the description hit")
                .containsExactly("widget-ancient", "recent");
        assertThat(names(search("?q=widget&sort=recent")))
                .as("sort=recent means what it says, even when relevance would order it differently")
                .containsExactly("recent", "widget-ancient");
    }

    @Test
    void percentAndUnderscoreAreSearchedForRatherThanTreatedAsWildcards() {
        // Without escaping, `%` matches everything and `_` matches any character, so the caller's
        // text would be read as a pattern language. The value is still a bound parameter, so this
        // is about meaning rather than about injection.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "has-percent", "100% coverage", "y");
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "has-underscore", "a_b naming", "y");

        assertThat(names(search("?q=%25")))
                .as("a literal percent matches only the skill containing one")
                .containsExactly("has-percent");
        assertThat(names(search("?q=a_b")))
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
            String query = "?q=widget&limit=3" + (cursor == null ? "" : "&cursor=" + cursor);
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
        String issued = JSON.readTree(get("/api/v1/skills?q=widget&limit=1", token()).body())
                .get("next_cursor").asText();
        String ordering = JSON.readTree(new String(Base64.getUrlDecoder().decode(issued),
                StandardCharsets.UTF_8)).get("o").asText();
        String tampered = CursorCodec.encode(ordering,
                List.of("abc", "2026-09-28T00:00:00Z", "01M3HTG7GCCVBGRPAFFSVSF12W"));

        HttpResponse<String> response =
                get("/api/v1/skills?q=widget&limit=1&cursor=" + tampered, token());

        assertThat(response.statusCode()).as("body was: %s", response.body()).isEqualTo(400);
        assertThat(response.body()).contains("invalid_request");

        // The timestamp half of the same check, and the reason it is a round-trip rather than a
        // parse: the keyset compares updated_at as *text*, so a parseable-but-different spelling of
        // the same instant moves the page boundary without any error anywhere.
        String offsetForm = CursorCodec.encode(ordering,
                List.of("0", "2026-09-28T00:00:00+00:00", "01M3HTG7GCCVBGRPAFFSVSF12W"));

        assertThat(get("/api/v1/skills?q=widget&limit=1&cursor=" + offsetForm, token()).statusCode())
                .as("the same instant written differently is not the text this ordering compares")
                .isEqualTo(400);

        // The score half, with a value that is a number to Java and not to PostgreSQL:
        // Integer.parseInt takes every Unicode decimal digit, the AS integer cast takes ASCII only.
        String arabicIndic = CursorCodec.encode(ordering,
                List.of("٣", "2026-09-28T00:00:00Z", "01M3HTG7GCCVBGRPAFFSVSF12W"));

        assertThat(get("/api/v1/skills?q=widget&limit=1&cursor=" + arabicIndic, token()).statusCode())
                .as("an Arabic-Indic three parses in Java and not in the cast the cursor feeds")
                .isEqualTo(400);
    }

    @Test
    void aCursorFromADifferentOrderingIsRejected() {
        // The cursor carries scores computed under one ordering. Resuming it under another would
        // compare tiers that share no scale, producing a page of arbitrary rows with no error.
        insertSkillsForPaging();
        String cursor = JSON.readTree(get("/api/v1/skills?q=widget&limit=1", token()).body())
                .get("next_cursor").asText();

        assertThat(get("/api/v1/skills?sort=recent&limit=1&cursor=" + cursor, token()).statusCode())
                .isEqualTo(400);
        assertThat(get("/api/v1/skills?q=widget&limit=1&cursor=" + cursor, token()).statusCode())
                .as("the same ordering it came from is accepted")
                .isEqualTo(200);

        // The ordering comparison runs first, so that `sort=recent` case is rejected by it — but
        // delete the comparison and it is *still* rejected, by the width check (relevance carries
        // three key parts, recency two). Which is why it never failed the mutation this test is
        // named for. A retuned weighting has the same width and a different scale, so only the
        // comparison can reject it, and that is the case it exists for.
        String retuned = CursorCodec.encode("relevance:1,2,3",
                List.of("0", "2026-09-28T00:00:00Z", "01M3HTG7GCCVBGRPAFFSVSF12W"));

        assertThat(get("/api/v1/skills?q=widget&limit=1&cursor=" + retuned, token()).statusCode())
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

        assertThat(search("").size()).isEqualTo(20);
        assertThat(search("?limit=100").size()).isEqualTo(100);
        assertThat(search("?limit=101").size())
                .as("a larger request is clamped to the documented ceiling")
                .isEqualTo(100);
        assertThat(search("?limit=100000").size())
                .as("and a much larger one is clamped too, not merely refused")
                .isEqualTo(100);
        assertThat(get("/api/v1/skills?limit=0", token()).statusCode())
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

        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, current_version_id, created_by, created_at, updated_at)
                VALUES (:id, :namespace, :name, :title, :description, '{}', 'private', :version,
                        :user, :at, :at)
                """)
                .param("id", skillId).param("namespace", namespaceId).param("name", name)
                .param("title", title).param("description", description).param("version", versionId)
                .param("user", userId).param("at", updatedAt)
                .update();

        jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, version, digest, file_count,
                                           total_bytes, changelog, source, submitted_by, submitted_at,
                                           state, state_at, title, description, frontmatter)
                VALUES (:id, :skill, 1, '1.0.0', :digest, 0, 0, '', 'zip', :user, :at,
                        'published', :at, :title, :description, :frontmatter)
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
                VALUES (:skill, :grantee, 'viewer', :by, '2026-09-28T00:00:00Z')
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
