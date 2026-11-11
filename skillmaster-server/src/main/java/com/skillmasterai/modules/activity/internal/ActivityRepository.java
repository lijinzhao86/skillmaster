package com.skillmasterai.modules.activity.internal;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.activity.ActivityEvent;
import com.skillmasterai.modules.activity.ActivityKinds;
import com.skillmasterai.modules.activity.InvokeActivity;
import com.skillmasterai.modules.activity.SearchActivity;
import com.skillmasterai.modules.activity.SearchHit;
import com.skillmasterai.modules.activity.UsageHistory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * M17's only SQL. Every statement names only its two tables (`TableOwnershipTest`).
 *
 * <p><strong>Why searches have their own insertion path.</strong> A page 2 request is the same
 * query, so it appends to the page 1 row instead of creating a second search. The alternative —
 * one row per request — would make "搜索次数" count HTTP calls and show the same query twice.
 *
 * <p><strong>Why invokes have one too.</strong> Attribution is a read of the previous 30 minutes
 * followed by an insert; doing it at read time in the page would make the page pay for it forever.
 */
public final class ActivityRepository {

    /** The attribution and page-merge window. One constant: two would drift. */
    static final int WINDOW_SECONDS = 30 * 60;

    /** The most result rows a search stores across all its pages (PRD A23/B5). */
    static final int MAX_RESULTS = 50;

    /** A cap on the rows locked while merging one page. A same-query window is normally 1. */
    private static final int MERGE_CANDIDATES = 20;

    /** A cap on the searches examined for attribution. The normal window has 0–3. */
    private static final int INVOKE_CANDIDATES = 50;

    /**
     * The skill keyword, when there is one. A search row matches through the addresses its stored
     * results carry; a call matches through its own {@code skill_address}.
     *
     * <p><strong>{@code CASE} guards the cast, not just {@code IS JSON}.</strong> SQL does not
     * promise left-to-right evaluation, so a damaged {@code detail} could still reach
     * {@code ::jsonb}; inside {@code CASE} only the chosen branch is evaluated, and
     * {@code jsonb_array_elements(NULL)} yields no rows. A damaged row therefore stops matching
     * instead of failing the page.
     */
    private static final String SKILL_CLAUSE = " AND (CAST(:skill AS TEXT) IS NULL OR ("
            + " (e.kind = :search AND e.detail IS JSON AND EXISTS ("
            + "   SELECT 1 FROM jsonb_array_elements(CASE WHEN e.detail IS JSON THEN"
            + "     CASE WHEN jsonb_typeof(e.detail::jsonb -> 'results') = 'array'"
            + "       THEN e.detail::jsonb -> 'results' END END) AS r"
            + "   WHERE r ->> 'address' ILIKE :like ESCAPE '\\'))"
            + " OR (e.kind = :invoke AND e.skill_address ILIKE :like ESCAPE '\\')))";

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Runnable partitionEnsurer;

    public ActivityRepository(JdbcClient jdbc, ObjectMapper json, Runnable partitionEnsurer) {
        this.jdbc = jdbc;
        this.json = json;
        this.partitionEnsurer = partitionEnsurer;
    }

    public void record(ActivityEvent event) {
        insert(event.actorUserId(), event.ownerUserId(), event.kind(), event.skillId(),
                event.skillAddress(), event.versionId(), event.versionLabel(), event.target(),
                write(event.detail()), null, null, event.at());
    }

    public void recordSearch(SearchActivity search) {
        if (search.continuation()) {
            Optional<Row> match = findMergeTarget(search);
            if (match.isPresent()) {
                merge(match.get(), search);
                return;
            }
        }
        insertSearch(search);
    }

    public void recordInvoke(InvokeActivity invoke) {
        String since = since(invoke.at());
        List<Row> candidates = jdbc.sql("SELECT id, at, detail FROM activity_event"
                        + " WHERE actor_user_id = :actor AND kind = :kind"
                        + " AND at >= :since AND at <= :at"
                        + " ORDER BY at DESC, id DESC LIMIT " + INVOKE_CANDIDATES)
                .param("actor", invoke.actorUserId())
                .param("kind", ActivityKinds.SEARCH)
                .param("since", since)
                .param("at", invoke.at())
                .query((rs, rowNum) -> new Row(rs.getLong("id"), rs.getString("at"),
                        rs.getString("detail")))
                .list();

        for (Row candidate : candidates) {
            List<String> ids = skillIds(candidate.detail());
            if (ids == null) {
                continue;
            }
            int index = ids.indexOf(invoke.skillId());
            if (index >= 0) {
                insert(invoke.actorUserId(), invoke.ownerUserId(), ActivityKinds.INVOKE,
                        invoke.skillId(), invoke.skillAddress(), invoke.versionId(),
                        invoke.versionLabel(), null, "{}", candidate.id(), index + 1,
                        invoke.at());
                return;
            }
        }
        insert(invoke.actorUserId(), invoke.ownerUserId(), ActivityKinds.INVOKE,
                invoke.skillId(), invoke.skillAddress(), invoke.versionId(), invoke.versionLabel(),
                null, "{}", null, null, invoke.at());
    }

    /**
     * The newest same-query search this page may join, locked for the read-modify-write.
     *
     * <p>"The same question" includes the narrowing, so filters are always compared — an empty map
     * only equals another empty map. Both planes therefore behave alike: a page of `scope=public`
     * cannot continue a `scope=library` search with the same words.
     */
    private Optional<Row> findMergeTarget(SearchActivity search) {
        String since = since(search.at());
        List<Row> candidates = jdbc.sql("SELECT id, at, detail FROM activity_event"
                        + " WHERE actor_user_id = :actor AND kind = :kind AND target = :query"
                        + " AND at >= :since AND at <= :at"
                        + " ORDER BY at DESC, id DESC LIMIT " + MERGE_CANDIDATES + " FOR UPDATE")
                .param("actor", search.actorUserId())
                .param("kind", ActivityKinds.SEARCH)
                .param("query", search.query())
                .param("since", since)
                .param("at", search.at())
                .query((rs, rowNum) -> new Row(rs.getLong("id"), rs.getString("at"),
                        rs.getString("detail")))
                .list();

        for (Row candidate : candidates) {
            SearchDetail detail = detail(candidate.detail());
            if (detail == null) {
                continue;
            }
            if (!search.filters().equals(detail.filters())) {
                continue;
            }
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private void merge(Row target, SearchActivity search) {
        SearchDetail old = detail(target.detail());
        if (old == null) {
            insertSearch(search);
            return;
        }
        List<StoredResult> results = new ArrayList<>(old.results());
        Set<String> seen = new HashSet<>(old.skillIds());
        for (SearchHit hit : search.results()) {
            if (results.size() >= MAX_RESULTS) {
                break;
            }
            if (!seen.add(hit.skillId())) {
                continue;
            }
            results.add(new StoredResult(hit.skillId(), hit.address(), results.size() + 1));
        }
        Long total = old.total() != null ? old.total() : search.total();
        SearchDetail next = new SearchDetail(results, idsOf(results), total, results.size(),
                old.pages() + 1, old.filters());

        // The update names the same actor + at + id the index lookup used: there is no primary
        // key, by design, and this is the only statement that ever updates a log row.
        jdbc.sql("UPDATE activity_event SET detail = :detail"
                        + " WHERE actor_user_id = :actor AND kind = :kind AND at = :at AND id = :id")
                .param("detail", write(next))
                .param("actor", search.actorUserId())
                .param("kind", ActivityKinds.SEARCH)
                .param("at", target.at())
                .param("id", target.id())
                .update();
    }

    private void insertSearch(SearchActivity search) {
        List<StoredResult> results = new ArrayList<>();
        for (SearchHit hit : search.results()) {
            if (results.size() >= MAX_RESULTS) {
                break;
            }
            results.add(new StoredResult(hit.skillId(), hit.address(), results.size() + 1));
        }
        SearchDetail detail = new SearchDetail(results, idsOf(results), search.total(),
                results.size(), 1, search.filters());
        insert(search.actorUserId(), null, ActivityKinds.SEARCH, null, null, null, null,
                search.query(), write(detail), null, null, search.at());
    }

    private void insert(String actor, String owner, String kind, String skillId, String address,
            String versionId, String versionLabel, String target, String detail, Long searchEventId,
            Integer searchRank, String at) {
        String sql = "INSERT INTO activity_event (actor_user_id, owner_user_id, kind, skill_id,"
                + " skill_address, version_id, version_label, target, detail,"
                + " search_event_id, search_rank, at)"
                + " VALUES (:actor, :owner, :kind, :skill, :address, :versionId,"
                + " :versionLabel, :target, :detail, :searchId, :searchRank, :at)";
        try {
            jdbc.sql(sql)
                    .param("actor", actor)
                    .param("owner", owner)
                    .param("kind", kind)
                    .param("skill", skillId)
                    .param("address", address)
                    .param("versionId", versionId)
                    .param("versionLabel", versionLabel)
                    .param("target", target)
                    .param("detail", detail)
                    .param("searchId", searchEventId)
                    .param("searchRank", searchRank)
                    .param("at", at)
                    .update();
        } catch (org.springframework.dao.DataAccessException failure) {
            // The partition job is the guarantee; this is the belt for the moment it has not run
            // yet (a fresh schema in a test context, a deploy that starts serving between the
            // migration and the job). One retry, after asking for the current weeks.
            if (!missingPartition(failure)) {
                throw failure;
            }
            partitionEnsurer.run();
            jdbc.sql(sql)
                    .param("actor", actor)
                    .param("owner", owner)
                    .param("kind", kind)
                    .param("skill", skillId)
                    .param("address", address)
                    .param("versionId", versionId)
                    .param("versionLabel", versionLabel)
                    .param("target", target)
                    .param("detail", detail)
                    .param("searchId", searchEventId)
                    .param("searchRank", searchRank)
                    .param("at", at)
                    .update();
        }
    }

    private static boolean missingPartition(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause.getMessage() != null
                    && cause.getMessage().contains("no partition of relation")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Whether this account has any row the page would ever show — a search or a call, the filters
     * aside. Sign-ins and file reads are recorded too, but they are not list rows: counting them
     * would make "暂无使用记录" unreachable for anyone who has logged in.
     */
    public boolean hasAny(String actorUserId) {
        String since = Timestamps.format(
                Instant.now().minus(UsageHistory.WINDOW_DAYS, ChronoUnit.DAYS));
        Boolean found = jdbc.sql("SELECT EXISTS (SELECT 1 FROM activity_event"
                        + " WHERE actor_user_id = :actor AND at >= :since"
                        + " AND (kind = :search OR kind = :invoke))")
                .param("actor", actorUserId)
                .param("since", since)
                .param("search", ActivityKinds.SEARCH)
                .param("invoke", ActivityKinds.INVOKE)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(found);
    }

    public UsageHistory.Page interactions(String actorUserId, int page, int limit,
            UsageHistory.Window window) {
        // A call whose search has just fallen out of the window is shown as a direct call: it
        // happened inside the window, so hiding it would make the page disagree with the stats
        // that do count it.
        //
        // **The stale searches are looked up by the (actor, at) index, never by id.** An earlier
        // shape asked `NOT EXISTS (... WHERE s.id = e.search_event_id)`; `activity_event` has no
        // index on `id` (deliberately — it has no primary key), so that subquery scanned the whole
        // 90-day window for every call. Only searches in the last 30 minutes before the window can
        // have produced a call inside it, so that small index range is all this needs.
        List<Long> staleIds = staleSearchIds(actorUserId, window.since());

        String visible = " e.kind = :search OR (e.kind = :invoke AND (e.search_event_id IS NULL";
        if (!staleIds.isEmpty()) {
            visible += " OR e.search_event_id IN (:staleIds)";
        }
        visible += "))";

        var countQuery = bindWindow(jdbc.sql("SELECT count(*) FROM activity_event e"
                        + " WHERE e.actor_user_id = :actor AND e.at >= :since AND e.at < :until"
                        + " AND (" + visible + ")" + SKILL_CLAUSE),
                actorUserId, window, staleIds);
        long total = countQuery.query(Long.class).single();

        var pageQuery = bindWindow(jdbc.sql("SELECT e.id, e.at, e.kind, e.skill_address,"
                        + " e.version_label, e.target, e.detail, e.search_event_id"
                        + " FROM activity_event e"
                        + " WHERE e.actor_user_id = :actor AND e.at >= :since AND e.at < :until"
                        + " AND (" + visible + ")" + SKILL_CLAUSE
                        + " ORDER BY e.at DESC, e.id DESC LIMIT :limit OFFSET :offset"),
                actorUserId, window, staleIds)
                .param("limit", limit)
                // Long arithmetic: `page` is an int the caller controls, and (int) MAX_VALUE × 100
                // overflows negative, which PostgreSQL answers with an error (a 500 for a page
                // number that should just be empty).
                .param("offset", (long) (page - 1) * limit);
        List<ListRow> rows = pageQuery
                .query((rs, rowNum) -> new ListRow(rs.getLong("id"), rs.getString("at"),
                        rs.getString("kind"), rs.getString("skill_address"),
                        rs.getString("version_label"), rs.getString("target"),
                        rs.getString("detail"), (Long) rs.getObject("search_event_id")))
                .list();

        List<Long> searchIds = rows.stream()
                .filter(row -> ActivityKinds.SEARCH.equals(row.kind()))
                .map(ListRow::id)
                .toList();
        Map<Long, List<UsageHistory.Invocation>> invocations =
                invocations(searchIds, actorUserId, window);

        List<UsageHistory.Interaction> items = new ArrayList<>();
        for (ListRow row : rows) {
            if (ActivityKinds.SEARCH.equals(row.kind())) {
                SearchDetail detail = detail(row.detail());
                List<UsageHistory.Result> results = detail == null ? List.of()
                        : detail.results().stream()
                                .map(result -> new UsageHistory.Result(result.address(),
                                        result.rank()))
                                .toList();
                items.add(new UsageHistory.Search(row.id(), row.at(), row.target(),
                        detail == null ? null : detail.total(),
                        detail == null ? results.size() : detail.returned(),
                        results, invocations.getOrDefault(row.id(), List.of())));
            } else {
                items.add(new UsageHistory.Direct(row.id(), row.at(), row.skillAddress(),
                        row.versionLabel()));
            }
        }
        return new UsageHistory.Page(items, total, page, limit);
    }

    private Map<Long, List<UsageHistory.Invocation>> invocations(List<Long> searchIds,
            String actorUserId, UsageHistory.Window window) {
        if (searchIds.isEmpty()) {
            return Map.of();
        }
        // **The calls are windowed too.** A search inside the window can lead to a call after
        // `until` (attribution looks 30 minutes ahead); showing that call under the row while the
        // stats exclude it is the one way the page and its numbers can disagree.
        List<Map.Entry<Long, UsageHistory.Invocation>> rows = jdbc.sql(
                        "SELECT search_event_id, skill_address, version_label, at, search_rank"
                                + " FROM activity_event WHERE kind = :kind AND actor_user_id = :actor"
                                + " AND at >= :since AND at < :until"
                                + " AND search_event_id IN (:ids) ORDER BY at, id")
                .param("kind", ActivityKinds.INVOKE)
                .param("actor", actorUserId)
                .param("since", window.since())
                .param("until", window.until())
                .param("ids", searchIds)
                .query((rs, rowNum) -> Map.entry(rs.getLong("search_event_id"),
                        new UsageHistory.Invocation(rs.getString("skill_address"),
                                rs.getString("version_label"), rs.getString("at"),
                                rs.getInt("search_rank"))))
                .list();
        Map<Long, List<UsageHistory.Invocation>> grouped = new java.util.LinkedHashMap<>();
        for (Map.Entry<Long, UsageHistory.Invocation> row : rows) {
            grouped.computeIfAbsent(row.getKey(), key -> new ArrayList<>()).add(row.getValue());
        }
        return grouped;
    }

    public UsageHistory.Stats stats(String actorUserId, UsageHistory.Window window) {
        // `converted` must use the same visible-search rule as the detail list: a call whose
        // search has left the window is shown as a direct call there, so it cannot also count as
        // "this search led to a call".
        List<Long> staleIds = staleSearchIds(actorUserId, window.since());
        String convertedExpr = "count(DISTINCT search_event_id) FILTER (WHERE kind = :invoke"
                + " AND search_event_id IS NOT NULL";
        if (!staleIds.isEmpty()) {
            convertedExpr += " AND search_event_id NOT IN (:staleIds)";
        }
        convertedExpr += ") AS converted,";

        var query = bindWindow(jdbc.sql("SELECT"
                        + " count(*) FILTER (WHERE kind = :invoke) AS calls,"
                        + " count(*) FILTER (WHERE kind = :search) AS searches,"
                        + " " + convertedExpr
                        + " count(DISTINCT skill_id) FILTER (WHERE kind = :invoke) AS skills,"
                        + " max(at) FILTER (WHERE kind = :invoke) AS latest"
                        + " FROM activity_event e WHERE e.actor_user_id = :actor"
                        + " AND e.at >= :since AND e.at < :until"
                        + " AND e.kind IN (:search, :invoke)" + SKILL_CLAUSE),
                actorUserId, window, staleIds);
        Map<String, Object> row = query.query().singleRow();

        long calls = number(row.get("calls"));
        long searches = number(row.get("searches"));
        long converted = Math.min(number(row.get("converted")), searches);
        long skills = number(row.get("skills"));

        var topQuery = jdbc.sql("SELECT max(e.skill_address) AS skill,"
                        + " count(*) AS calls, max(e.at) AS last FROM activity_event e"
                        + " WHERE e.actor_user_id = :actor AND e.kind = :invoke"
                        + " AND e.at >= :since AND e.at < :until"
                        + " AND e.skill_id IS NOT NULL"
                        + " AND (CAST(:skill AS TEXT) IS NULL OR e.skill_address ILIKE :like ESCAPE '\\')"
                        + " GROUP BY e.skill_id"
                        + " ORDER BY calls DESC, last DESC LIMIT 20")
                .param("actor", actorUserId)
                .param("invoke", ActivityKinds.INVOKE)
                .param("since", window.since())
                .param("until", window.until())
                .param("skill", window.skill())
                .param("like", likePattern(window.skill()));
        List<UsageHistory.TopSkill> top = topQuery
                .query((rs, rowNum) -> new UsageHistory.TopSkill(rs.getString("skill"),
                        rs.getLong("calls"), rs.getString("last")))
                .list();

        return new UsageHistory.Stats(calls, searches, converted, searches - converted, skills,
                (String) row.get("latest"), top);
    }

    public List<UsageHistory.ActorInvokes> collaboratorDays(String skillId,
            UsageHistory.Window window) {
        return jdbc.sql("SELECT actor_user_id, count(*) AS invokes, max(at) AS last"
                        + " FROM activity_event WHERE skill_id = :skill AND kind = :kind"
                        + " AND at >= :since AND at < :until GROUP BY actor_user_id")
                .param("skill", skillId)
                .param("kind", ActivityKinds.INVOKE)
                .param("since", window.since())
                .param("until", window.until())
                .query((rs, rowNum) -> new UsageHistory.ActorInvokes(rs.getString("actor_user_id"),
                        rs.getLong("invokes"), rs.getString("last")))
                .list();
    }

    public UsageHistory.PublicRange skillRange(String skillId, UsageHistory.Window window) {
        Map<String, Object> row = jdbc.sql("SELECT count(*) AS calls,"
                        + " count(DISTINCT actor_user_id) AS accounts, max(at) AS last"
                        + " FROM activity_event WHERE skill_id = :skill AND kind = :kind"
                        + " AND at >= :since AND at < :until")
                .param("skill", skillId)
                .param("kind", ActivityKinds.INVOKE)
                .param("since", window.since())
                .param("until", window.until())
                .query().singleRow();
        return new UsageHistory.PublicRange(number(row.get("calls")), number(row.get("accounts")),
                (String) row.get("last"));
    }

    public UsageHistory.Mine mine(String skillId, String actorUserId, UsageHistory.Window window) {
        List<UsageHistory.MineVersion> byVersion = jdbc.sql(
                        "SELECT version_label, count(*) AS calls, max(at) AS last"
                                + " FROM activity_event WHERE actor_user_id = :actor"
                                + " AND skill_id = :skill AND kind = :kind"
                                + " AND at >= :since AND at < :until"
                                + " GROUP BY version_label ORDER BY last DESC, calls DESC")
                .param("actor", actorUserId)
                .param("skill", skillId)
                .param("kind", ActivityKinds.INVOKE)
                .param("since", window.since())
                .param("until", window.until())
                .query((rs, rowNum) -> new UsageHistory.MineVersion(rs.getString("version_label"),
                        null, rs.getLong("calls"), rs.getString("last")))
                .list();
        long calls = byVersion.stream().mapToLong(UsageHistory.MineVersion::calls).sum();
        String latest = byVersion.stream().map(UsageHistory.MineVersion::last)
                .filter(java.util.Objects::nonNull).max(String::compareTo).orElse(null);
        return new UsageHistory.Mine(calls, byVersion.size(), latest, byVersion);
    }

    public List<InvokeDay> invokeDays(String from, String until) {
        return jdbc.sql("SELECT skill_id, actor_user_id, max(owner_user_id) AS owner,"
                        + " substr(at, 1, 10) AS day, count(*) AS invokes, max(at) AS last_at"
                        + " FROM activity_event WHERE kind = :kind AND skill_id IS NOT NULL"
                        + " AND at >= :from AND at < :until"
                        + " GROUP BY skill_id, actor_user_id, day")
                .param("kind", ActivityKinds.INVOKE)
                .param("from", from)
                .param("until", until)
                .query((rs, rowNum) -> new InvokeDay(rs.getString("skill_id"),
                        rs.getString("actor_user_id"), rs.getString("owner"),
                        rs.getString("day"), rs.getInt("invokes"), rs.getString("last_at")))
                .list();
    }

    public void upsertInvokes(InvokeDay row) {
        jdbc.sql("INSERT INTO skill_collaborator_day"
                        + " (skill_id, actor_user_id, owner_user_id, day, invokes, versions, last_at)"
                        + " VALUES (:skill, :actor, :owner, :day, :invokes, 0, :last)"
                        + " ON CONFLICT (skill_id, actor_user_id, day) DO UPDATE SET"
                        + " invokes = EXCLUDED.invokes,"
                        + " last_at = GREATEST(skill_collaborator_day.last_at, EXCLUDED.last_at),"
                        + " owner_user_id = COALESCE(EXCLUDED.owner_user_id,"
                        + " skill_collaborator_day.owner_user_id)")
                .param("skill", row.skillId())
                .param("actor", row.actorUserId())
                .param("owner", row.ownerUserId())
                .param("day", row.day())
                .param("invokes", row.invokes())
                .param("last", row.lastAt())
                .update();
    }

    public void upsertVersions(String skillId, String actorUserId, String day, int versions,
            String lastAt) {
        jdbc.sql("INSERT INTO skill_collaborator_day"
                        + " (skill_id, actor_user_id, owner_user_id, day, invokes, versions, last_at)"
                        + " VALUES (:skill, :actor, NULL, :day, 0, :versions, :last)"
                        + " ON CONFLICT (skill_id, actor_user_id, day) DO UPDATE SET"
                        + " versions = EXCLUDED.versions,"
                        + " last_at = GREATEST(skill_collaborator_day.last_at, EXCLUDED.last_at)")
                .param("skill", skillId)
                .param("actor", actorUserId)
                .param("day", day)
                .param("versions", versions)
                .param("last", lastAt)
                .update();
    }

    /**
     * The newest day the rollup already holds, which is where the next catch-up starts.
     *
     * <p>A watermark rather than a boolean: after a weekend offline, "has any row" answers yes and
     * the missed days would stay missing forever. This one names the last day done, so the job can
     * fill everything after it.
     */
    public java.util.Optional<String> latestSummaryDay() {
        return jdbc.sql("SELECT max(day) FROM skill_collaborator_day")
                .query(String.class)
                .optional();
    }

    /** The rollup's retention: the owner view never reads beyond the detail window. */
    public int deleteSummaryBefore(String day) {
        return jdbc.sql("DELETE FROM skill_collaborator_day WHERE day < :day")
                .param("day", day)
                .update();
    }

    public record InvokeDay(String skillId, String actorUserId, String ownerUserId, String day,
            int invokes, String lastAt) {
    }

    private static long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /**
     * Searches just older than the window that can still own a call inside it.
     *
     * <p>Only the 30 minutes before {@code since} matter: attribution never looks further back.
     * The lookup uses the {@code (actor, at) WHERE kind='search'} index; asking by {@code id}
     * instead would scan, because the log deliberately has no index on its identity column.
     */
    private List<Long> staleSearchIds(String actorUserId, String since) {
        String staleFrom = Timestamps.format(Instant.parse(since).minusSeconds(WINDOW_SECONDS));
        return jdbc.sql("SELECT id FROM activity_event"
                        + " WHERE actor_user_id = :actor AND kind = :search"
                        + " AND at >= :staleFrom AND at < :since")
                .param("actor", actorUserId)
                .param("search", ActivityKinds.SEARCH)
                .param("staleFrom", staleFrom)
                .param("since", since)
                .query(Long.class)
                .list();
    }

    /**
     * Every parameter the window and skill fragments name. Binding an unused nullable parameter is
     * harmless; leaving one out is a driver error, so they are all bound in one place.
     */
    private static JdbcClient.StatementSpec bindWindow(JdbcClient.StatementSpec query,
            String actorUserId, UsageHistory.Window window, List<Long> staleIds) {
        JdbcClient.StatementSpec bound = query
                .param("actor", actorUserId)
                .param("since", window.since())
                .param("until", window.until())
                .param("search", ActivityKinds.SEARCH)
                .param("invoke", ActivityKinds.INVOKE)
                .param("skill", window.skill())
                .param("like", likePattern(window.skill()));
        return staleIds.isEmpty() ? bound : bound.param("staleIds", staleIds);
    }

    /** A LIKE pattern for a contains match; the keyword's own wildcards are literal. */
    private static String likePattern(String skill) {
        if (skill == null) {
            return null;
        }
        String escaped = skill.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private record ListRow(long id, String at, String kind, String skillAddress,
            String versionLabel, String target, String detail, Long searchEventId) {
    }

    /** The stored copy, or null when this row predates the shape or has been damaged. */
    private SearchDetail detail(String raw) {
        try {
            return json.readValue(raw, SearchDetail.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<String> skillIds(String raw) {
        SearchDetail detail = detail(raw);
        return detail == null ? null : detail.skillIds();
    }

    private String write(Object value) {
        return json.writeValueAsString(value);
    }

    private static String since(String at) {
        return Timestamps.format(Instant.parse(at).minusSeconds(WINDOW_SECONDS));
    }

    private static List<String> idsOf(List<StoredResult> results) {
        return results.stream().map(StoredResult::skillId).toList();
    }

    private record Row(long id, String at, String detail) {
    }
}
