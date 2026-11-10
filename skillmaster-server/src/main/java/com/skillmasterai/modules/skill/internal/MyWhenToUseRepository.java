package com.skillmasterai.modules.skill.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The caller's own "when I would look for this" sentence (v59).
 *
 * <p><strong>One row per person and skill.</strong> A write replaces the row, a clear deletes it;
 * there is no history and no version, because the sentence only steers the author's own search.
 *
 * <p><strong>It is not tied to the library entry.</strong> The table has no dependency on
 * {@code skill_library_join} or a grant: the sentence survives leaving the library and a withdrawn
 * share, and comes back with the entry. Only the account and the skill cascade.
 */
public final class MyWhenToUseRepository {

    private final JdbcClient jdbc;

    public MyWhenToUseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> find(String userId, String skillId) {
        List<String> rows = jdbc.sql("""
                SELECT when_to_use FROM my_skill_when_to_use
                 WHERE user_id = :userId AND skill_id = :skillId
                """)
                .param("userId", userId)
                .param("skillId", skillId)
                .query((rs, rowNum) -> rs.getString("when_to_use"))
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** The caller's sentences for a page of skills, in one query. */
    public Map<String, String> forSkills(String userId, List<String> skillIds) {
        if (skillIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> found = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT skill_id, when_to_use FROM my_skill_when_to_use
                 WHERE user_id = :userId AND skill_id = ANY(:skillIds)
                """)
                .param("userId", userId)
                .param("skillIds", skillIds.toArray(String[]::new))
                .query((rs, rowNum) -> Map.entry(rs.getString("skill_id"),
                        rs.getString("when_to_use")))
                .list()
                .forEach(entry -> found.put(entry.getKey(), entry.getValue()));
        return found;
    }

    /** Every sentence this caller has written; the personal ranking reads them in one pass. */
    public Map<String, String> allFor(String userId) {
        Map<String, String> found = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT skill_id, when_to_use FROM my_skill_when_to_use WHERE user_id = :userId
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> Map.entry(rs.getString("skill_id"),
                        rs.getString("when_to_use")))
                .list()
                .forEach(entry -> found.put(entry.getKey(), entry.getValue()));
        return found;
    }

    /**
     * Inserts or replaces the sentence.
     *
     * @return whether the stored text changed. The comparison is in the statement, so two requests
     *         carrying the same text cannot both report a write, and {@code updated_at} does not
     *         move for a no-op
     */
    public boolean set(String userId, String skillId, String whenToUse, String updatedAt) {
        return jdbc.sql("""
                INSERT INTO my_skill_when_to_use (user_id, skill_id, when_to_use, updated_at)
                VALUES (:userId, :skillId, :whenToUse, :updatedAt)
                ON CONFLICT (user_id, skill_id) DO UPDATE
                   SET when_to_use = EXCLUDED.when_to_use,
                       updated_at = EXCLUDED.updated_at
                 WHERE my_skill_when_to_use.when_to_use IS DISTINCT FROM EXCLUDED.when_to_use
                """)
                .param("userId", userId)
                .param("skillId", skillId)
                .param("whenToUse", whenToUse)
                .param("updatedAt", updatedAt)
                .update() > 0;
    }

    /** @return whether a row was removed; no row is a no-op, not an error */
    public boolean clear(String userId, String skillId) {
        return jdbc.sql("""
                DELETE FROM my_skill_when_to_use
                 WHERE user_id = :userId AND skill_id = :skillId
                """)
                .param("userId", userId)
                .param("skillId", skillId)
                .update() > 0;
    }
}
