package com.skillmasterai.modules.audit.internal;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Audit rows in {@code audit_event} — the only table M10 owns (`skill_stat`, which the baseline
 * carried for P1's usage counters, went with v3's 范围 #5: nothing ever read or wrote it).
 *
 * <p>The {@code id} column is never supplied: it is {@code GENERATED ALWAYS AS IDENTITY}, and
 * naming it would be an error rather than an override.
 */
public final class PgAuditLog implements AuditLog {

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
}
