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

    private final JdbcClient jdbc;

    public SkillCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One page of a listing.
     *
     * <p><strong>The text query admits a row; the score only ranks it.</strong> Since v14 the
     * admitting half is per word and every word has to match somewhere — name, title, description,
     * {@code when_to_use}, or a public tag (ADR 0084). The score's weights and formula are
     * deliberately untouched: tags and {@code when_to_use} add recall, not rank.
     *
     * @param categoryKeys  the category filter, "any of" — a skill is on one shelf, so two shelves
     *                      widen rather than narrow (v14)
     * @param publicTagKeys the public-tag filter, "all of" — each key becomes its own EXISTS
     */
    public List<SkillCatalogService.CatalogRow> page(List<String> namespaceFilterIds, String queryText,
            SkillCatalogService.RankingWeights weights, boolean byRelevance, boolean publicOnly,
            List<String> categoryKeys, List<String> publicTagKeys,
            List<String> afterKey, int limit, Integer offset, Caller caller) {

        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("namespaceFilterIds", namespaceFilterIds.isEmpty() ? null
                : namespaceFilterIds.toArray(String[]::new));
        params.put("ownNamespaceId", caller.ownNamespaceId());
        params.put("callerId", caller.userId());
        params.put("writeAndUp", SkillPredicates.WRITE_AND_UP.toArray(String[]::new));
        // The score's pattern is the whole query, unchanged from v13: the per-word filter decides
        // inclusion, this only ranks. A null pattern makes every CASE take ELSE, so a browsing
        // request scores zero without a branch.
        params.put("pattern", LikePattern.containing(queryText));
        params.put("weightName", weights.name());
        params.put("weightTitle", weights.title());
        params.put("weightDescription", weights.description());
        params.put("publicOnly", publicOnly);

        String textFilter = SqlFilters.text(queryText, params);
        String categoryFilter = SqlFilters.categories(categoryKeys, params);
        String publicTagFilter = SqlFilters.publicTags(publicTagKeys, params);

        String sql = SkillPredicates.IDENTIFIED + """
                SELECT t.id, t.namespace_id, t.name, t.description, t.when_to_use,
                       t.updated_at, t.relevance, t.source, t.state, t.version, t.deleted_at,
                       t.category_key, t.category_name, t.active
                FROM (
                    SELECT s.id, s.namespace_id, s.name, s.description, s.when_to_use, s.source,
                           -- The list's "更新于" (V21, extended in v14): the current published
                           -- version's submission time, the newest draft's when nothing has been
                           -- published, and the row's creation time when every version was
                           -- discarded. Deliberately not `s.updated_at` — that column records when
                           -- something went live, which is a different fact and moves less often.
                           %s AS updated_at,
                           %s AS relevance,
                           -- The display state. Derived, never stored: the three facts are already
                           -- columns and a fourth would need a backfill for something the query
                           -- already knows (TD §谓词).
                           CASE WHEN s.deleted_at IS NOT NULL THEN 'deleted'
                                WHEN s.current_version_id IS NULL THEN 'draft'
                                WHEN s.offline_at IS NOT NULL THEN 'offline'
                                ELSE 'live' END AS state,
                           s.current_version_name AS version,
                           s.deleted_at,
                           s.category_key,
                           c.name AS category_name,
                           -- "Effective" is about the library entry, so a skill the caller has no
                           -- relationship with is never active however public it is.
                           (s.source IS NOT NULL AND %s) AS active
                    FROM identified s
                    LEFT JOIN skill_category c ON c.key = s.category_key
                    -- The discovery scope narrows to what may be served (v14): public, published,
                    -- online, not deleted. False leaves the older consumer search as it was —
                    -- everything the caller may already see and open.
                    WHERE (NOT :publicOnly OR (%s))
                      -- The cast is not decoration, and it is the same trap the text pattern
                      -- documents: a bare null parameter leaves PostgreSQL unable to infer a type
                      -- on either side, so `? IS NULL` fails the query outright. Which is exactly
                      -- the browsing case — no filter, no query text, and a 500 for the first
                      -- request every client makes. It matters twice as much for an array, whose
                      -- element type Postgres has no other way to guess.
                      AND (CAST(:namespaceFilterIds AS text[]) IS NULL
                           OR s.namespace_id = ANY(:namespaceFilterIds))
                      -- The access filter, in the statement that finds the rows (3.4). Since v14
                      -- it is the shared definition; the namespace parameter above narrows and
                      -- never widens.
                      AND %s
                      %s
                      %s
                      %s
                ) t
                WHERE :hasCursor = FALSE OR %s
                ORDER BY %s
                LIMIT :limit
                OFFSET :offset
                """.formatted(SkillPredicates.LIST_UPDATED_AT, RELEVANCE, SkillPredicates.ACTIVE,
                SkillPredicates.DISCOVERABLE, SkillPredicates.VISIBLE, textFilter, categoryFilter,
                publicTagFilter, keyset(byRelevance), order(byRelevance));

        params.put("hasCursor", afterKey != null);
        params.put("limit", limit);
        params.put("offset", offset == null ? 0 : offset);
        List<String> key = afterKey != null ? afterKey : blankKey(byRelevance);
        for (int i = 0; i < key.size(); i++) {
            params.put("k" + i, key.get(i));
        }

        return jdbc.sql(sql).params(params)
                .query((rs, rowNum) -> new SkillCatalogService.CatalogRow(
                        rs.getString("id"),
                        rs.getString("namespace_id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("when_to_use"),
                        rs.getString("updated_at"),
                        rs.getInt("relevance"),
                        rs.getString("source"),
                        rs.getString("state"),
                        rs.getString("version"),
                        rs.getString("deleted_at"),
                        rs.getString("category_key"),
                        rs.getString("category_name"),
                        rs.getBoolean("active"))).list();
    }


    /**
     * How many rows the same filters select, before the page is cut.
     *
     * <p>The browser's discovery pager needs a total: it draws page numbers, and a page count
     * without one is a guess. The API plane does not ask for it — a keyset cursor already answers
     * "is there more" — so this is only ever called by the console's read.
     */
    public long count(List<String> namespaceFilterIds, String queryText, boolean publicOnly,
            List<String> categoryKeys, List<String> publicTagKeys, Caller caller) {
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("namespaceFilterIds", namespaceFilterIds.isEmpty() ? null
                : namespaceFilterIds.toArray(String[]::new));
        params.put("ownNamespaceId", caller.ownNamespaceId());
        params.put("callerId", caller.userId());
        params.put("publicOnly", publicOnly);

        String sql = SkillPredicates.IDENTIFIED + """
                SELECT count(*) FROM identified s
                WHERE (NOT :publicOnly OR (%s))
                  AND (CAST(:namespaceFilterIds AS text[]) IS NULL
                       OR s.namespace_id = ANY(:namespaceFilterIds))
                  AND %s
                  %s
                  %s
                  %s
                """.formatted(SkillPredicates.DISCOVERABLE, SkillPredicates.VISIBLE,
                SqlFilters.text(queryText, params), SqlFilters.categories(categoryKeys, params),
                SqlFilters.publicTags(publicTagKeys, params));
        return jdbc.sql(sql).params(params).query(Long.class).single();
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
     * <p><strong>It names only M12's tables — {@code skill} and {@code skill_grant}, plus
     * {@code skill_library_join} through the shared identity CTE (v14).</strong> The facts
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
        return jdbc.sql(SkillPredicates.IDENTIFIED + """
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
                       -- The list's "更新于": the current published version's time, the newest
                       -- draft's when nothing has been published, and the row's creation time when
                       -- every version was discarded (v14).
                       %s AS updated_at
                FROM identified s
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
                                  OR s.grant_role = ANY(:writeAndUp) )) )
                -- Newest submission first — the time the card shows.
                ORDER BY updated_at DESC, s.id ASC
                LIMIT :limit
                """.formatted(SkillPredicates.LIST_UPDATED_AT))
                .param("ownNamespaceId", caller.ownNamespaceId())
                .param("callerId", caller.userId())
                .param("writeAndUp", SkillPredicates.WRITE_AND_UP.toArray(String[]::new))
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
