package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.modules.skill.AccessRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and writes {@code skill_access_request} — one of M12's three tables (ADR 0052/0053).
 *
 * <p>Nothing in the access predicates reads this table, and that is the decision rather than an
 * omission: a request grants nothing. The only writes are the requester's own row and the
 * approver's decision on it — which deletes the row and, on approval, writes {@code skill_grant}
 * through {@link SkillGrantRepository}, the same path a direct grant takes.
 */
public final class SkillAccessRequestRepository {

    private final JdbcClient jdbc;

    public SkillAccessRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the request, or replaces the one already there.
     *
     * <p>The primary key is {@code (skill, requester)}, so resubmitting is the same intent
     * expressed again — a new wanted rung, but not a second row. {@code created_at} moves with it:
     * the list shows when the current request was made, not when this person first ever asked.
     */
    public void upsert(String skillId, String requesterId, String wantedRole, String at) {
        jdbc.sql("""
                INSERT INTO skill_access_request (skill_id, requester_id, wanted_role, created_at)
                VALUES (:skillId, :requesterId, :wantedRole, :at)
                ON CONFLICT (skill_id, requester_id)
                DO UPDATE SET wanted_role = EXCLUDED.wanted_role,
                              created_at  = EXCLUDED.created_at
                """)
                .param("skillId", skillId)
                .param("requesterId", requesterId)
                .param("wantedRole", wantedRole)
                .param("at", at)
                .update();
    }

    /** @return the rung that was pending, or empty when this person had not asked */
    public Optional<String> delete(String skillId, String requesterId) {
        return jdbc.sql("DELETE FROM skill_access_request"
                        + " WHERE skill_id = :skillId AND requester_id = :requesterId"
                        + " RETURNING wanted_role")
                .param("skillId", skillId)
                .param("requesterId", requesterId)
                .query(String.class)
                .optional();
    }

    /** The one request this person has on this skill, if they have one. */
    public Optional<AccessRequest> find(String skillId, String requesterId) {
        return jdbc.sql("""
                SELECT requester_id, wanted_role, created_at
                FROM skill_access_request
                WHERE skill_id = :skillId AND requester_id = :requesterId
                """)
                .param("skillId", skillId)
                .param("requesterId", requesterId)
                .query((rs, rowNum) -> new AccessRequest(
                        rs.getString("requester_id"),
                        rs.getString("wanted_role"),
                        rs.getString("created_at")))
                .optional();
    }

    /**
     * Everyone waiting on this skill, oldest first.
     *
     * <p>Oldest first rather than newest, unlike the grant list: this is a queue of things to answer,
     * and the one that has waited longest is the one to answer next. Ties break on the requester id
     * so the order is total.
     */
    public List<AccessRequest> pendingFor(String skillId) {
        return jdbc.sql("""
                SELECT requester_id, wanted_role, created_at
                FROM skill_access_request
                WHERE skill_id = :skillId
                ORDER BY created_at ASC, requester_id ASC
                """)
                .param("skillId", skillId)
                .query((rs, rowNum) -> new AccessRequest(
                        rs.getString("requester_id"),
                        rs.getString("wanted_role"),
                        rs.getString("created_at")))
                .list();
    }
}
