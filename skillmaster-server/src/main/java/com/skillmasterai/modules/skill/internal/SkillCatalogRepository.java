package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillCatalogService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Pages {@code skill}, ordered and filtered.
 *
 * <p>Names only M12's own tables. The access filter is a comparison of this module's own columns and
 * one {@code LEFT JOIN} to its own {@code skill_grant}, so §3.4's requirement that the filter live
 * in the query costs nothing structurally: nothing of M4's is joined, and there is therefore no way
 * to write the query without it.
 *
 * <p><strong>It is a disjunction since ADR 0034, and the namespace is a filter rather than the
 * predicate.</strong> Before sharing, one namespace <em>was</em> the access rule; now the caller may
 * also see skills granted to them elsewhere, and the namespace parameter narrows the result set
 * within what they may already see — §4.2 is explicit that it narrows and never widens.
 *
 * <p>The relevance score is computed here rather than in the caller because it has to be, for the
 * keyset to work: the cursor carries the score of the last row returned, so the next page's
 * predicate compares against a value this query produced. Handing the ranking to the caller would
 * mean re-computing it in a second language and hoping the two agreed.
 *
 * <p>{@code updated_at} is compared as text. Safe only because
 * {@link com.skillmasterai.common.Timestamps} writes RFC3339 UTC truncated to seconds: fixed
 * width, fixed offset, zero padded, so lexicographic order <em>is</em> chronological order.
 */
public final class SkillCatalogRepository {

    private static final String PATTERN = ":pattern " + LikePattern.ESCAPE_CLAUSE;

    /** What each field is worth — scoring, and deliberately not what decides inclusion. */
    private static final String RELEVANCE = """
            ( CASE WHEN s.name        ILIKE %1$s THEN :weightName        ELSE 0 END
            + CASE WHEN s.title       ILIKE %1$s THEN :weightTitle       ELSE 0 END
            + CASE WHEN s.description ILIKE %1$s THEN :weightDescription ELSE 0 END )
            """.formatted(PATTERN);

    /**
     * Which rows the text query admits.
     *
     * <p>Separate from {@link #RELEVANCE} because they answer different questions — this one says
     * whether a row is a result at all, that one says how good a result it is — and because
     * conflating them is a mistake that hides well: the first version of this query used the
     * pattern only in the score, so every search returned every skill, ranked. It passed the tests
     * that existed, because each of them happened to insert only matching rows.
     *
     * <p>A null pattern admits everything, which is §4.2's "empty q returns by sort".
     */
    private static final String TEXT_FILTER = """
            ( CAST(:pattern AS text) IS NULL
              OR s.name        ILIKE %s
              OR s.title       ILIKE %s
              OR s.description ILIKE %s )
            """.formatted(PATTERN, PATTERN, PATTERN);

    private final JdbcClient jdbc;

    public SkillCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SkillCatalogService.CatalogRow> page(List<String> namespaceFilterIds, String queryText,
            SkillCatalogService.RankingWeights weights, boolean byRelevance, List<String> afterKey,
            int limit, Caller caller) {

        String sql = """
                SELECT t.id, t.namespace_id, t.name, t.description, t.when_to_use,
                       t.updated_at, t.relevance
                FROM (
                    SELECT s.id, s.namespace_id, s.name, s.description, s.when_to_use,
                           -- The list's "更新于" (V21): the current published version's submission
                           -- time, falling back to the newest draft for a skill nothing has been
                           -- published from. Deliberately not `s.updated_at` — that column records
                           -- when something went live, which is a different fact and moves less
                           -- often. The two are separate on purpose (ADR 0053).
                           COALESCE(s.current_version_submitted_at, s.newest_draft_submitted_at)
                               AS updated_at,
                           %s AS relevance
                    FROM skill s
                    -- The pointer is written only by publishing (ADR 0031), so a skill with no
                    -- current version is one nothing has been published from — a draft-only skill,
                    -- which the consumption plane must not list. Since V21 this is an explicit
                    -- predicate rather than a join: the row carries what the card needs, and the
                    -- pointer's integrity is the publish path's to keep.
                    WHERE s.deleted_at IS NULL
                      AND s.current_version_id IS NOT NULL
                      -- An offline skill is not served anywhere, and that includes being found:
                      -- a result that cannot be opened is worse than no result (v6).
                      AND s.offline_at IS NULL
                      -- The cast is not decoration, and it is the same trap the text pattern
                      -- documents: a bare null parameter leaves PostgreSQL unable to infer a type
                      -- on either side, so `? IS NULL` fails the query outright. Which is exactly
                      -- the browsing case — no filter, no query text, and a 500 for the first
                      -- request every client makes. It matters twice as much for an array, whose
                      -- element type Postgres has no other way to guess.
                      AND (CAST(:namespaceFilterIds AS text[]) IS NULL
                           OR s.namespace_id = ANY(:namespaceFilterIds))
                      -- The access filter, in the statement that finds the rows (3.4). The namespace
                      -- parameter above narrows; this is what decides whether a row is a result at
                      -- all. Three branches, all M12's own columns: mine, granted to me, or handed to
                      -- everybody by the public rung (ADR 0048). The public branch has to be here as
                      -- well as in `permitted()` — changing one and not the other is the
                      -- "searchable but unopenable" failure ADR 0048 §后果 names.
                      AND ( s.namespace_id = :ownNamespaceId
                            OR EXISTS (SELECT 1 FROM skill_grant g
                                        WHERE g.skill_id = s.id AND g.grantee_id = :callerId)
                            OR s.visibility = 'public' )
                      AND %s
                ) t
                WHERE :hasCursor = FALSE OR %s
                ORDER BY %s
                LIMIT :limit
                """.formatted(RELEVANCE, TEXT_FILTER, keyset(byRelevance), order(byRelevance));

        // Null rather than an empty array, because that is what the predicate above tests for: `= ANY`
        // of nothing selects nothing, which is a different question from "no filter at all".
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("namespaceFilterIds", namespaceFilterIds.isEmpty() ? null
                        : namespaceFilterIds.toArray(String[]::new))
                .param("ownNamespaceId", caller.ownNamespaceId())
                .param("callerId", caller.userId())
                // A null pattern makes each ILIKE null, and CASE WHEN null takes the ELSE branch —
                // so "no text" scores zero for every row without a branch in the SQL. The cast is
                // not decoration: a bare null parameter leaves PostgreSQL unable to infer a type
                // on either side of ILIKE, and it would fail on the one path nobody exercises
                // until a client browses without typing anything.
                .param("pattern", LikePattern.containing(queryText))
                .param("weightName", weights.name())
                .param("weightTitle", weights.title())
                .param("weightDescription", weights.description())
                .param("hasCursor", afterKey != null)
                .param("limit", limit);

        List<String> key = afterKey != null ? afterKey : blankKey(byRelevance);
        for (int i = 0; i < key.size(); i++) {
            statement = statement.param("k" + i, key.get(i));
        }

        return statement.query((rs, rowNum) -> new SkillCatalogService.CatalogRow(
                rs.getString("id"),
                rs.getString("namespace_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("when_to_use"),
                rs.getString("updated_at"),
                rs.getInt("relevance"))).list();
    }

    /**
     * The author's own skills, newest submission first, drafts included.
     *
     * <p><strong>Not a search.</strong> {@link #page} answers "what may this caller find", which is
     * why it requires a current version: a skill with nothing published is not a result and has no
     * card. This answers "what do I have", which is the opposite question — a skill whose versions
     * are all drafts is exactly what the author needs to see, and it is the only place it can be
     * seen at all (ADR 0031).
     *
     * <p><strong>It names only {@code skill} and {@code skill_grant} (ADR 0053).</strong> The facts
     * that used to come from {@code skill_version} — the current version's name and digest, the
     * draft count, the newest draft — are the V21 projection columns, written by M7 at submit /
     * discard / publish time. That is why this no longer joins the content tables.
     *
     * <p>No cursor. The listing is one person's own skills and a hard limit is honest about that;
     * M8's cursor is keyed on a relevance score this query does not compute, so reusing it would
     * page through nothing.
     *
     * @param limit rows to return; the caller picks a number and says why
     * @param deleted false for the live list, true for the "deleted" one (ADR 0050). The two are
     *                separate lists in the product, not two views of one list, which is why this is
     *                a different query rather than a filter on the result: the deleted list holds
     *                only skills the caller owns, and nothing shared can ever appear in it
     */
    public List<SkillCatalogService.AuthorRow> authorPage(int limit, boolean deleted,
            Caller caller) {
        return jdbc.sql("""
                SELECT s.id, s.namespace_id, s.name,
                       -- Which version the card is named after. The live one when there is one
                       -- (`skill.title` is its projection, ADR 0031), and the newest draft
                       -- otherwise. `skill.title` is also written by findOrCreate, so for a skill
                       -- nothing has been published from it is frozen at the first submission for
                       -- ever — a title no version holds any more if that first one was later
                       -- discarded. The fallback to it covers the all-discarded row.
                       CASE WHEN s.current_version_id IS NOT NULL THEN s.title
                            ELSE COALESCE(s.newest_title, s.title) END AS title,
                       CASE WHEN s.current_version_id IS NOT NULL THEN s.description
                            ELSE COALESCE(s.newest_description, s.description) END AS description,
                       s.visibility, s.deleted_at, s.offline_at,
                       s.current_version_name AS current_version, s.current_digest,
                       s.draft_count AS drafts,
                       s.newest_draft_version, s.newest_draft_digest,
                       -- The list's "更新于": the current published version's time, or the newest
                       -- draft's for a row nothing has been published from. NULL when every version
                       -- was discarded — the row still exists, and the page says so.
                       COALESCE(s.current_version_submitted_at, s.newest_draft_submitted_at)
                           AS updated_at
                FROM skill s
                -- What the author has. The live list is the caller's own skills plus the ones
                -- shared at `write` and above (ADR 0047: each rung includes the ones below, so
                -- `maintain` and `admin` are here too). Everything below `write` is deliberately
                -- absent - the author plane shows drafts and diffs, and a draft is a version you
                -- could act on. The deleted list is a different question with one answer:
                -- ownership, because only the owner can restore (ADR 0050).
                WHERE (CASE WHEN :deleted THEN s.deleted_at IS NOT NULL
                            ELSE s.deleted_at IS NULL END)
                  AND ( (:deleted AND s.namespace_id = :ownNamespaceId)
                        OR (NOT :deleted
                            AND ( s.namespace_id = :ownNamespaceId
                                  OR EXISTS (SELECT 1 FROM skill_grant g
                                              WHERE g.skill_id = s.id
                                                AND g.grantee_id = :callerId
                                                AND g.role IN ('write', 'maintain', 'admin')) )) )
                -- Newest submission first — the time the card shows. A row with neither a live
                -- version nor a draft (every version discarded) sorts last rather than disappearing.
                ORDER BY updated_at DESC NULLS LAST, s.id ASC
                LIMIT :limit
                """)
                .param("ownNamespaceId", caller.ownNamespaceId())
                .param("callerId", caller.userId())
                .param("deleted", deleted)
                .param("limit", limit)
                .query((rs, rowNum) -> new SkillCatalogService.AuthorRow(
                        rs.getString("id"),
                        rs.getString("namespace_id"),
                        rs.getString("name"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("visibility"),
                        rs.getString("current_version"),
                        rs.getString("current_digest"),
                        rs.getInt("drafts"),
                        // Both null when there is no draft at all, which is a different thing from
                        // a draft whose author declared no version name — that one has a digest.
                        rs.getString("newest_draft_version"),
                        rs.getString("newest_draft_digest"),
                        rs.getString("updated_at"),
                        rs.getString("deleted_at"),
                        rs.getString("offline_at")))
                .list();
    }

    /**
     * Everything strictly after the row the cursor names.
     *
     * <p>Strict, because that row has already been returned and {@code <=} would repeat it. Total,
     * because {@code id} is the last component, so rows that tie on everything else still have a
     * defined order and the boundary falls between them rather than through them.
     */
    private static String keyset(boolean byRelevance) {
        return byRelevance
                ? "(t.relevance < CAST(:k0 AS integer)"
                        + " OR (t.relevance = CAST(:k0 AS integer) AND t.updated_at < :k1)"
                        + " OR (t.relevance = CAST(:k0 AS integer) AND t.updated_at = :k1"
                        + "     AND t.id > :k2))"
                : "(t.updated_at < :k0 OR (t.updated_at = :k0 AND t.id > :k1))";
    }

    private static String order(boolean byRelevance) {
        return byRelevance
                ? "t.relevance DESC, t.updated_at DESC, t.id ASC"
                : "t.updated_at DESC, t.id ASC";
    }

    /**
     * Placeholders for the first page.
     *
     * <p>Bound because the parameters are named in the SQL either way; they are never read, since
     * {@code :hasCursor = FALSE} satisfies the predicate before them. Typed correctly all the same
     * — an integer parameter given an empty string would fail on the cast rather than be ignored.
     */
    private static List<String> blankKey(boolean byRelevance) {
        List<String> values = new ArrayList<>();
        if (byRelevance) {
            values.add("0");
        }
        values.add("");
        values.add("");
        return values;
    }
}
