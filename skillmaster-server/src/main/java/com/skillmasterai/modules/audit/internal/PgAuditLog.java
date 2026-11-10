package com.skillmasterai.modules.audit.internal;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditCounts;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Audit rows in {@code audit_event} — the only table M10 owns (`skill_stat`, which the baseline
 * carried for P1's usage counters, went with v3's 范围 #5: nothing ever read or wrote it).
 *
 * <p>The {@code id} column is never supplied: it is {@code GENERATED ALWAYS AS IDENTITY}, and
 * naming it would be an error rather than an override.
 */
public final class PgAuditLog implements AuditLog, AuditCounts {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public PgAuditLog(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(AuditEvent event) {
        jdbc.sql("""
                INSERT INTO audit_event (actor_user_id, action, target_type, target_id, detail, at)
                VALUES (:actorUserId, :action, :targetType, :targetId, :detail, :at)
                """)
                .param("actorUserId", event.actorUserId())
                .param("action", event.action())
                .param("targetType", event.targetType())
                .param("targetId", event.targetId())
                .param("detail", objectMapper.writeValueAsString(event.detail()))
                .param("at", Timestamps.now())
                .update();
    }

    @Override
    public void eraseDetailsFor(String userId) {
        jdbc.sql("UPDATE audit_event SET detail = '{}' WHERE actor_user_id = :id")
                .param("id", userId)
                .update();
    }

    @Override
    public List<Submission> submissions(String from, String until) {
        // The day is a prefix of `at`, which is a fixed-width RFC3339 UTC string; no parsing and
        // no session time zone enter this query.
        return jdbc.sql("SELECT target_id, actor_user_id, substr(at, 1, 10) AS day,"
                        + " count(*) AS versions, max(at) AS last_at FROM audit_event"
                        + " WHERE action = 'submit' AND target_type = 'skill'"
                        + " AND at >= :from AND at < :until"
                        + " GROUP BY target_id, actor_user_id, day")
                .param("from", from)
                .param("until", until)
                .query((rs, rowNum) -> new Submission(rs.getString("target_id"),
                        rs.getString("actor_user_id"), rs.getString("day"),
                        rs.getInt("versions"), rs.getString("last_at")))
                .list();
    }

    @Override
    public List<SubmissionCount> submissionsOfSkill(String skillId, String from, String until) {
        return jdbc.sql("SELECT actor_user_id, count(*) AS versions, max(at) AS last_at"
                        + " FROM audit_event WHERE action = 'submit' AND target_type = 'skill'"
                        + " AND target_id = :skill AND at >= :from AND at < :until"
                        + " GROUP BY actor_user_id")
                .param("skill", skillId)
                .param("from", from)
                .param("until", until)
                .query((rs, rowNum) -> new SubmissionCount(rs.getString("actor_user_id"),
                        rs.getInt("versions"), rs.getString("last_at")))
                .list();
    }
}
