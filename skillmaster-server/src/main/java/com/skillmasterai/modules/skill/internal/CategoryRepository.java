package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.modules.skill.SkillCategoryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The category vocabulary and a skill's one shelf (v14, ADR 0078).
 *
 * <p><strong>The vocabulary is platform data.</strong> It changes by migration and never by a user;
 * this repository reads it and writes only the assignment column on {@code skill}. It names M12's
 * two tables — {@code skill_category} and {@code skill} — and nothing else.
 *
 * <p><strong>Retired words are left out of every read.</strong> A stopped word is still returned:
 * the picker shows it greyed, an existing assignment still filters, and the CLI marks it
 * {@code （停用）}. A retired word is the end state and appears nowhere.
 */
public final class CategoryRepository {

    private final JdbcClient jdbc;

    public CategoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every word that still appears, in picker order, with the public count. */
    public List<SkillCategoryService.CategoryRow> withPublicTotals() {
        Map<String, Object> params = new LinkedHashMap<>();
        // The vocabulary is the same for everyone; the CTE's caller parameters are bound but never
        // select a row (the join condition asks DISCOVERABLE alone).
        params.put("ownNamespaceId", "");
        params.put("callerId", "");

        return jdbc.sql(SkillPredicates.IDENTIFIED + """
                SELECT c.key, c.name, c.state, count(s.id) AS public_total
                FROM skill_category c
                LEFT JOIN identified s
                       ON s.category_key = c.key AND %s
                WHERE c.state <> 'retired'
                GROUP BY c.key, c.name, c.state, c.sort_order
                ORDER BY c.sort_order
                """.formatted(SkillPredicates.DISCOVERABLE))
                .params(params)
                .query((rs, rowNum) -> new SkillCategoryService.CategoryRow(
                        rs.getString("key"), rs.getString("name"), rs.getString("state"),
                        rs.getLong("public_total")))
                .list();
    }

    /** Every word including retired ones — the name-collision rule says retired names stay taken. */
    public List<SkillCategoryService.CategoryRow> all() {
        return jdbc.sql("""
                SELECT key, name, state FROM skill_category ORDER BY sort_order
                """)
                .query((rs, rowNum) -> new SkillCategoryService.CategoryRow(
                        rs.getString("key"), rs.getString("name"), rs.getString("state"), 0))
                .list();
    }

    public Optional<SkillCategoryService.CategoryRow> byKey(String key) {
        return jdbc.sql("""
                SELECT key, name, state FROM skill_category WHERE key = :key
                """)
                .param("key", key)
                .query((rs, rowNum) -> new SkillCategoryService.CategoryRow(
                        rs.getString("key"), rs.getString("name"), rs.getString("state"), 0))
                .optional();
    }

    /**
     * The skill's own assignment, or null.
     *
     * <p>Read on its own rather than joined into {@link SkillRepository#standing}: the write path
     * needs the previous value for the audit row, and {@code standing} is the access shape.
     */
    public Optional<String> categoryOf(String skillId) {
        List<String> rows = jdbc.sql("SELECT category_key FROM skill WHERE id = :id")
                .param("id", skillId)
                .query((rs, n) -> rs.getString("category_key"))
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * Writes the one assignment. Deliberately not {@code updated_at}: filing a skill is not
     * content, and the library's "更新于" must not move (TD §规范化与计数).
     *
     * @return whether a row changed. The condition is in the statement, not only in the caller's
     *         earlier read: two requests carrying the same new key race, and the one that loses
     *         must answer {@code changed=false} rather than claim a write the other one made
     */
    public boolean set(String skillId, String key) {
        return jdbc.sql("""
                UPDATE skill SET category_key = :key
                 WHERE id = :skillId AND category_key IS DISTINCT FROM :key
                """)
                .param("skillId", skillId)
                .param("key", key)
                .update() > 0;
    }

    /** @return whether a shelf was removed; the same conditional-write rule as {@link #set} */
    public boolean clear(String skillId) {
        return jdbc.sql("""
                UPDATE skill SET category_key = NULL
                 WHERE id = :skillId AND category_key IS NOT NULL
                """)
                .param("skillId", skillId)
                .update() > 0;
    }
}
