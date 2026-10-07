package com.skillmasterai.modules.version.internal;

import com.skillmasterai.modules.version.SkillGrant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and writes {@code skill_grant} — M7's fourth table (ADR 0034).
 *
 * <p>Here rather than in M4 because its foreign key points at {@code skill}, which this module owns,
 * and because the predicate it feeds lives in this module's statements: "which skills are granted to
 * this caller" is one disjunct of the query that finds a skill, and computing it in another module
 * would mean either a join across the rule-1 line or a set of ids carried back in.
 */
public final class SkillGrantRepository {

    private final JdbcClient jdbc;

    public SkillGrantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Shares the skill, or changes what was already shared.
     *
     * <p>{@code DO UPDATE} rather than {@code DO NOTHING}: granting twice is how a role is changed,
     * so the second call must move the row rather than leave the first role in place. It also makes
     * the whole request idempotent, which matters because the caller cannot know whether a grant is
     * already there without asking — and asking first would be a race.
     */
    public void upsert(String skillId, String granteeId, String role, String grantedBy, String at) {
        jdbc.sql("""
                INSERT INTO skill_grant (skill_id, grantee_id, role, granted_by, created_at)
                VALUES (:skillId, :granteeId, :role, :grantedBy, :at)
                ON CONFLICT (skill_id, grantee_id)
                DO UPDATE SET role = EXCLUDED.role, granted_by = EXCLUDED.granted_by
                """)
                .param("skillId", skillId)
                .param("granteeId", granteeId)
                .param("role", role)
                .param("grantedBy", grantedBy)
                .param("at", at)
                .update();
    }

    /** @return the role, or empty when this skill was never shared with that account */
    public Optional<String> roleOf(String skillId, String granteeId) {
        return jdbc.sql("SELECT role FROM skill_grant WHERE skill_id = :skillId"
                        + " AND grantee_id = :granteeId")
                .param("skillId", skillId)
                .param("granteeId", granteeId)
                .query(String.class)
                .optional();
    }

    /**
     * Everyone this skill is shared with, newest first.
     *
     * <p>Ordered by {@code created_at} rather than left to the database: the page that renders this
     * is showing a history of decisions, and an unordered result would reshuffle between two loads
     * of the same list. Ties break on the grantee id so the order is total.
     */
    public List<SkillGrant> grantsOf(String skillId) {
        return jdbc.sql("SELECT grantee_id, role, created_at FROM skill_grant"
                        + " WHERE skill_id = :skillId ORDER BY created_at DESC, grantee_id")
                .param("skillId", skillId)
                .query((rs, rowNum) -> new SkillGrant(
                        rs.getString("grantee_id"), rs.getString("role"), rs.getString("created_at")))
                .list();
    }

    /**
     * Withdraws the grant.
     *
     * @return the role that was removed, or empty when there was nothing to remove — the caller needs
     *         to tell those apart for the audit row, not for the response
     */
    public Optional<String> delete(String skillId, String granteeId) {
        return jdbc.sql("DELETE FROM skill_grant WHERE skill_id = :skillId"
                        + " AND grantee_id = :granteeId RETURNING role")
                .param("skillId", skillId)
                .param("granteeId", granteeId)
                .query(String.class)
                .optional();
    }

}
