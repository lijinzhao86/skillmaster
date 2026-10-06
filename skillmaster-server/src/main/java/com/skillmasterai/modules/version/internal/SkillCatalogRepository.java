package com.skillmasterai.modules.version.internal;

import com.skillmasterai.modules.version.SkillCatalogService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Pages {@code skill}, ordered and filtered.
 *
 * <p>Names only M7's own tables. The ownership filter is {@code skill.namespace_id = :namespaceId}
 * — a column of this module's table, so §3.4's requirement that the filter live in the query costs
 * nothing structurally: there is no join to another module for it, and therefore no way to write
 * the query without it.
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

    public List<SkillCatalogService.CatalogRow> page(String namespaceId, String queryText,
            SkillCatalogService.RankingWeights weights, boolean byRelevance, List<String> afterKey,
            int limit) {

        String sql = """
                SELECT t.id, t.name, t.title, t.description, t.visibility, t.number, t.digest,
                       t.updated_at, t.relevance
                FROM (
                    SELECT s.id, s.name, s.title, s.description, s.visibility, s.updated_at,
                           v.number AS number, v.digest AS digest, %s AS relevance
                    FROM skill s
                    -- Inner, not left: the pointer is written only by publishing (ADR 0031), so a
                    -- skill with no current version is one nothing has been published from — a
                    -- draft-only skill, which the consumption plane must not list. Two jobs, then:
                    -- that filter, and the only integrity check there is — current_version_id
                    -- carries no foreign key by design, to avoid a circular one. The detail path
                    -- reports a break loudly; a listing simply cannot represent one.
                    JOIN skill_version v ON v.id = s.current_version_id
                    WHERE s.deleted_at IS NULL
                      AND s.namespace_id = :namespaceId
                      AND %s
                ) t
                WHERE :hasCursor = FALSE OR %s
                ORDER BY %s
                LIMIT :limit
                """.formatted(RELEVANCE, TEXT_FILTER, keyset(byRelevance), order(byRelevance));

        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("namespaceId", namespaceId)
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
                rs.getString("name"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("visibility"),
                rs.getInt("number"),
                rs.getString("digest"),
                rs.getString("updated_at"),
                rs.getInt("relevance"))).list();
    }

    /**
     * The author's own skills, newest submission first, drafts included.
     *
     * <p><strong>Not a search.</strong> {@link #page} answers "what may this caller find", which is
     * why it inner-joins the pointer: a skill with nothing published is not a result and has no card.
     * This answers "what do I have", which is the opposite question and the reason the join is a
     * {@code LEFT} one — a skill whose versions are all drafts is exactly what the author needs to
     * see, and it is the only place it can be seen at all (ADR 0031). The two are separate methods
     * rather than one with a flag because a caller that got the flag wrong would either hide the
     * author's drafts or serve them to a consumer.
     *
     * <p>No cursor. The listing is one person's own skills and a hard limit is honest about that;
     * M8's cursor is keyed on a relevance score this query does not compute, so reusing it would
     * page through nothing.
     *
     * @param limit rows to return; the caller picks a number and says why
     */
    public List<SkillCatalogService.AuthorRow> authorPage(String namespaceId, int limit) {
        return jdbc.sql("""
                SELECT s.id, s.name,
                       -- Which version the card is named after. The live one when there is one, and
                       -- the newest submission otherwise: `skill.title` is written by findOrCreate and
                       -- by a publish and *never* by a submit, so for a skill nothing has been
                       -- published from it is frozen at the first submission for ever — a title no
                       -- version holds any more if that first one was later superseded or discarded.
                       -- The detail endpoint already answers "pointer, else newest non-discarded";
                       -- this makes the listing agree with it rather than name the skill differently
                       -- from the page it opens.
                       COALESCE(cv.title, a.newest_title, s.title) AS title,
                       COALESCE(cv.description, a.newest_description, s.description) AS description,
                       s.visibility,
                       cv.number AS current_number, cv.digest AS current_digest,
                       COALESCE(d.drafts, 0) AS drafts, d.newest_draft,
                       a.latest_submitted_at
                FROM skill s
                LEFT JOIN skill_version cv ON cv.id = s.current_version_id
                -- One pass for both facts about the drafts: how many there are, and which one a
                -- reader who wants to look at one should be sent to. It is the highest number
                -- rather than the latest timestamp because numbers are allocated in submission
                -- order and two submissions in the same second would tie.
                LEFT JOIN LATERAL (
                    SELECT count(*) AS drafts, max(v.number) AS newest_draft
                    FROM skill_version v
                    WHERE v.skill_id = s.id AND v.state = 'draft'
                ) d ON TRUE
                LEFT JOIN LATERAL (
                    SELECT max(v.submitted_at) AS latest_submitted_at,
                           -- The newest non-discarded version's own metadata, for the case above. Both
                           -- columns are read from the same row (the same ORDER BY in one aggregate
                           -- pass), so a card can never pair one version's title with another's
                           -- description. NULL when every version was discarded.
                           (array_agg(v.title ORDER BY v.number DESC))[1] AS newest_title,
                           (array_agg(v.description ORDER BY v.number DESC))[1] AS newest_description
                    FROM skill_version v
                    WHERE v.skill_id = s.id AND v.state <> 'discarded'
                ) a ON TRUE
                WHERE s.deleted_at IS NULL AND s.namespace_id = :namespaceId
                -- Newest submission first, not newest publish: an author who has just submitted
                -- something is looking for it, and every other row keeps its previous position.
                ORDER BY a.latest_submitted_at DESC NULLS LAST, s.id ASC
                LIMIT :limit
                """)
                .param("namespaceId", namespaceId)
                .param("limit", limit)
                .query((rs, rowNum) -> new SkillCatalogService.AuthorRow(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("visibility"),
                        // readInt would turn SQL null into 0, which is a version number that exists.
                        (Integer) rs.getObject("current_number"),
                        rs.getString("current_digest"),
                        rs.getInt("drafts"),
                        // Null when there is no draft at all, which is a different thing from 0.
                        (Integer) rs.getObject("newest_draft"),
                        rs.getString("latest_submitted_at")))
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
