package com.skillmasterai.modules.skill.internal;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The two statements behind the narrow delete (v37, ADR 0127).
 *
 * <p>In their own class so {@link SkillRepository} stays the size it was: this is one rule with two
 * statements, and the rule is small enough to read whole.
 */
public final class NeverPublishedDelete {

    private final JdbcClient jdbc;

    public NeverPublishedDelete(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deletes the skill only if no version of it ever went live.
     *
     * <p><strong>The condition is in the statement, not before it.</strong> Reading
     * {@code ever_published_at} and then deleting leaves a window in which somebody publishes the
     * skill; an UPDATE whose WHERE carries the condition cannot.
     *
     * @return the deleted skill's id, or empty when it was published or already deleted
     */
    public Optional<String> delete(String skillId, String at) {
        return jdbc.sql("""
                UPDATE skill SET deleted_at = :at, updated_at = :at
                WHERE id = :skillId AND deleted_at IS NULL AND ever_published_at IS NULL
                RETURNING id
                """)
                .param("skillId", skillId)
                .param("at", at)
                .query(String.class)
                .optional();
    }

    /**
     * The facts that explain a refused delete. Read only after {@link #delete} returned nothing, so
     * it picks the message rather than the outcome; a row that is gone answers "deleted", which is
     * the 404 the caller gets anyway.
     */
    public State state(String skillId) {
        return jdbc.sql("""
                SELECT deleted_at, ever_published_at FROM skill WHERE id = :skillId
                """)
                .param("skillId", skillId)
                .query((rs, rowNum) -> new State(
                        rs.getString("deleted_at") != null,
                        rs.getString("ever_published_at") != null))
                .optional()
                .orElse(new State(true, false));
    }

    public record State(boolean deleted, boolean everPublished) {
    }
}
