package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillCatalogService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The caller's library: joins, the two listings and their counters (v14, ADR 0082).
 *
 * <p><strong>A library entry is derived, and this is where the derivation is written.</strong>
 * "Mine" is the namespace, "shared with me" is a grant, and {@code skill_library_join} holds the one
 * source that has nowhere else to live. {@link SkillPredicates#IDENTIFIED} computes all three in one
 * place; every statement here reads its {@code source} rather than re-deriving one.
 *
 * <p><strong>The counters are three different numbers and are never reused.</strong>
 * {@code total_entries} is every entry including dormant ones; {@code effective_entries} is the
 * effective ones; {@code filtered_total} is what the current filters selected. The product's three
 * empty states are told apart by exactly these three, so collapsing them would make the page say
 * one thing for three different situations (PRD §展示与计数口径).
 *
 * <p>Nothing here opens a transaction: a listing is a read, and the writes run inside the use
 * case's transaction so the insert and its audit row commit together.
 */
public final class LibraryRepository {

    private static final String CARD_COLUMNS = """
            s.id, s.namespace_id, s.name, s.description, s.when_to_use, s.source,
            %s AS updated_at,
            CASE WHEN s.deleted_at IS NOT NULL THEN 'deleted'
                 WHEN s.current_version_id IS NULL THEN 'draft'
                 WHEN s.offline_at IS NOT NULL THEN 'offline'
                 ELSE 'live' END AS state,
            s.current_version_name AS version,
            s.deleted_at, s.category_key, c.name AS category_name,
            (s.source IS NOT NULL AND %s) AS active
            """.formatted(SkillPredicates.LIST_UPDATED_AT, SkillPredicates.ACTIVE);

    private final JdbcClient jdbc;

    public LibraryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One page, in the caller's own order. */
    public List<SkillCatalogService.CatalogRow> page(com.skillmasterai.modules.skill.SkillLibraryService.Query query,
            Caller caller) {
        Where where = where(query, caller, true);
        Map<String, Object> params = where.params();
        params.put("hasCursor", query.afterKey() != null);
        params.put("limit", query.limit());
        if (query.offset() != null) {
            params.put("offset", query.offset());
        }
        // Bound even on the first page: the predicate is `:hasCursor = FALSE OR …`, but every
        // placeholder in the statement still needs a value.
        List<String> key = query.afterKey() == null ? List.of("", "") : query.afterKey();
        for (int i = 0; i < key.size(); i++) {
            params.put("k" + i, key.get(i));
        }

        String sql = SkillPredicates.IDENTIFIED + """
                , page AS (
                    SELECT %s
                    FROM identified s
                    LEFT JOIN skill_category c ON c.key = s.category_key
                    %s
                )
                SELECT * FROM page t
                WHERE :hasCursor = FALSE OR %s
                ORDER BY %s
                LIMIT :limit
                %s
                """.formatted(CARD_COLUMNS, where.sql(),
                keyset(query.byName()), order(query.byName()),
                query.offset() == null ? "" : "OFFSET :offset");

        return jdbc.sql(sql).params(params)
                .query((rs, rowNum) -> new SkillCatalogService.CatalogRow(
                        rs.getString("id"),
                        rs.getString("namespace_id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("when_to_use"),
                        rs.getString("updated_at"),
                        0,
                        rs.getString("source"),
                        rs.getString("state"),
                        rs.getString("version"),
                        rs.getString("deleted_at"),
                        rs.getString("category_key"),
                        rs.getString("category_name"),
                        rs.getBoolean("active"))).list();
    }

    /** The current filters' size, before the page is cut. */
    public long filteredTotal(com.skillmasterai.modules.skill.SkillLibraryService.Query query,
            Caller caller) {
        Where where = where(query, caller, true);
        return jdbc.sql(SkillPredicates.IDENTIFIED + """
                SELECT count(*) FROM identified s
                LEFT JOIN skill_category c ON c.key = s.category_key
                %s
                """.formatted(where.sql()))
                .params(where.params())
                .query(Long.class)
                .single();
    }

    /** Every entry, including dormant ones and the caller's soft-deleted skills. */
    public long totalEntries(Caller caller) {
        return jdbc.sql(SkillPredicates.IDENTIFIED + """
                SELECT count(*) FROM identified s WHERE s.source IS NOT NULL
                """)
                .param("ownNamespaceId", caller.ownNamespaceId())
                .param("callerId", caller.userId())
                .query(Long.class)
                .single();
    }

    /** The entries that are effective: live + draft + offline. */
    public long effectiveEntries(Caller caller) {
        return jdbc.sql(SkillPredicates.IDENTIFIED + """
                SELECT count(*) FROM identified s
                 WHERE s.source IS NOT NULL AND %s
                """.formatted(SkillPredicates.ACTIVE))
                .param("ownNamespaceId", caller.ownNamespaceId())
                .param("callerId", caller.userId())
                .param("writeAndUp", SkillPredicates.WRITE_AND_UP.toArray(String[]::new))
                .query(Long.class)
                .single();
    }

    /**
     * The "recommended" batch: discoverable skills with no relationship to the caller at all.
     *
     * <p>{@code source IS NULL} is the "not in my library" test, and inside the discoverable domain
     * it is equivalent to "the entry is not effective" — a public, published, online skill either
     * has one of the three sources or has none (PRD §展示与计数口径).
     */
    public List<SkillCatalogService.CatalogRow> recommended(List<String> afterKey, int limit,
            Caller caller) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ownNamespaceId", caller.ownNamespaceId());
        params.put("callerId", caller.userId());
        params.put("writeAndUp", SkillPredicates.WRITE_AND_UP.toArray(String[]::new));
        params.put("hasCursor", afterKey != null);
        params.put("limit", limit);
        List<String> key = afterKey == null ? List.of("", "") : afterKey;
        for (int i = 0; i < key.size(); i++) {
            params.put("k" + i, key.get(i));
        }

        String sql = SkillPredicates.IDENTIFIED + """
                , recommended AS (
                    SELECT %s
                    FROM identified s
                    LEFT JOIN skill_category c ON c.key = s.category_key
                    WHERE s.source IS NULL AND %s
                )
                SELECT * FROM recommended t
                WHERE :hasCursor = FALSE OR %s
                ORDER BY %s
                LIMIT :limit
                """.formatted(CARD_COLUMNS, SkillPredicates.DISCOVERABLE, keyset(false),
                order(false));

        return jdbc.sql(sql).params(params)
                .query((rs, rowNum) -> new SkillCatalogService.CatalogRow(
                        rs.getString("id"),
                        rs.getString("namespace_id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("when_to_use"),
                        rs.getString("updated_at"),
                        0,
                        rs.getString("source"),
                        rs.getString("state"),
                        rs.getString("version"),
                        rs.getString("deleted_at"),
                        rs.getString("category_key"),
                        rs.getString("category_name"),
                        rs.getBoolean("active"))).list();
    }

    /**
     * Whether the caller holds a join reference, whatever source is displayed.
     *
     * <p>The row is the fact (PRD #3): a later grant can raise the displayed source to "shared",
     * and joining again is still a no-op because the reference is already there.
     */
    public boolean joined(String userId, String skillId) {
        return jdbc.sql("""
                SELECT 1 FROM skill_library_join
                 WHERE user_id = :userId AND skill_id = :skillId
                """)
                .param("userId", userId)
                .param("skillId", skillId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * The skill id behind one address, when the caller holds a join reference to it.
     *
     * <p>Deliberately not visibility-filtered: a reference outlives the skill's visibility, and
     * leaving must be able to remove it while the entry is dormant (PRD #44). The row is the
     * caller's own, so answering it reveals nothing they did not already know.
     */
    public Optional<String> joinedSkillId(String userId, String namespaceId, String name) {
        return jdbc.sql("""
                SELECT s.id
                FROM skill s
                JOIN skill_library_join j ON j.skill_id = s.id AND j.user_id = :userId
                WHERE s.namespace_id = :namespaceId AND s.name = :name
                """)
                .param("userId", userId)
                .param("namespaceId", namespaceId)
                .param("name", name)
                .query(String.class)
                .optional();
    }

    /**
     * Adds the reference.
     *
     * @return whether this call inserted it; a repeat is a no-op and answers false, including when
     *         two concurrent requests both passed the "not there yet" read
     */
    public boolean join(String userId, String skillId, String at) {
        return jdbc.sql("""
                INSERT INTO skill_library_join (user_id, skill_id, created_at)
                VALUES (:userId, :skillId, :at)
                ON CONFLICT (user_id, skill_id) DO NOTHING
                """)
                .param("userId", userId)
                .param("skillId", skillId)
                .param("at", at)
                .update() > 0;
    }

    /**
     * Removes the reference, whatever the displayed source is.
     *
     * @return whether a row was there. False is a 409 {@code LIBRARY_REFERENCE_MISSING} at the
     *         caller: reporting success would tell a person they had left something they never
     *         joined
     */
    public boolean leave(String userId, String skillId) {
        return jdbc.sql("""
                DELETE FROM skill_library_join
                 WHERE user_id = :userId AND skill_id = :skillId
                """)
                .param("userId", userId)
                .param("skillId", skillId)
                .update() > 0;
    }

    private static String order(boolean byName) {
        return byName ? "t.name ASC, t.id ASC" : "t.updated_at DESC, t.id ASC";
    }

    private static String keyset(boolean byName) {
        return byName
                ? "(t.name > :k0 OR (t.name = :k0 AND t.id > :k1))"
                : "(t.updated_at < :k0 OR (t.updated_at = :k0 AND t.id > :k1))";
    }

    /**
     * The clauses every statement over "my library" shares.
     *
     * @param withFilters true for both the page and {@code filtered_total}; the total/effective
     *                    counters do not use this builder at all and therefore never move with a
     *                    filter
     */
    private static Where where(com.skillmasterai.modules.skill.SkillLibraryService.Query query,
            Caller caller, boolean withFilters) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ownNamespaceId", caller.ownNamespaceId());
        params.put("callerId", caller.userId());
        params.put("writeAndUp", SkillPredicates.WRITE_AND_UP.toArray(String[]::new));

        StringBuilder sql = new StringBuilder("WHERE s.source IS NOT NULL");
        switch (query.show()) {
            case "live" -> sql.append(" AND ").append(SkillPredicates.ACTIVE)
                    .append(" AND s.current_version_id IS NOT NULL AND s.offline_at IS NULL");
            case "draft" -> sql.append(" AND ").append(SkillPredicates.ACTIVE)
                    .append(" AND s.current_version_id IS NULL");
            case "offline" -> sql.append(" AND ").append(SkillPredicates.ACTIVE)
                    .append(" AND s.offline_at IS NOT NULL");
            case "all" -> sql.append(" AND ").append(SkillPredicates.ACTIVE);
            case "deleted" -> sql.append(" AND s.source = 'created' AND s.deleted_at IS NOT NULL");
            default -> throw new IllegalStateException("unknown library show: " + query.show());
        }

        if (!query.namespaceIds().isEmpty()) {
            params.put("namespaceIds", query.namespaceIds().toArray(String[]::new));
            sql.append(" AND s.namespace_id = ANY(:namespaceIds)");
        }

        if (!query.sources().isEmpty()) {
            params.put("sources", query.sources().toArray(String[]::new));
            sql.append(" AND s.source = ANY(:sources)");
        }

        if (withFilters) {
            // `show=deleted` deliberately ignores the tag filters: the page removes them from the
            // address, and a filter the address still carries must not silently select nothing.
            if (!"deleted".equals(query.show())) {
                sql.append(SqlFilters.categories(query.categoryKeys(), params));
                sql.append(SqlFilters.publicTags(query.publicTagKeys(), params));
                sql.append(SqlFilters.myTags(query.myTagIds(), params));
            }
            // A search term is still meaningful on the deleted list; the tags are not.
            sql.append(SqlFilters.text(query.q(), params));
        }
        return new Where(sql.toString(), params);
    }

    private record Where(String sql, Map<String, Object> params) {
    }
}
