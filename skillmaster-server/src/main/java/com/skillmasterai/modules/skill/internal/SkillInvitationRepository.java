package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.modules.skill.SkillInvitation;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and writes {@code skill_invitation} — M12's fourth sharing table (v45, ADR 0138).
 *
 * <p>Nothing in the access predicates reads this table. An invitation is not a grant: before it is
 * accepted the invitee has exactly what they had before, which is why this is a separate object
 * rather than a {@code status} column on {@code skill_grant}.
 */
public final class SkillInvitationRepository {

    private static final String COLUMNS =
            "skill_id, invitee_id, role, invited_by, created_at, expires_at";

    private final JdbcClient jdbc;

    public SkillInvitationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SkillInvitation> find(String skillId, String inviteeId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_invitation"
                        + " WHERE skill_id = :skillId AND invitee_id = :inviteeId")
                .param("skillId", skillId)
                .param("inviteeId", inviteeId)
                .query(SkillInvitationRepository::map)
                .optional();
    }

    /** One pending invitation per person per skill; re-sharing moves the existing row. */
    public void upsert(String skillId, String inviteeId, String role, String invitedBy,
            String at, String expiresAt) {
        jdbc.sql("""
                INSERT INTO skill_invitation
                    (skill_id, invitee_id, role, invited_by, created_at, expires_at)
                VALUES (:skillId, :inviteeId, :role, :invitedBy, :at, :expiresAt)
                ON CONFLICT (skill_id, invitee_id)
                DO UPDATE SET role = EXCLUDED.role,
                              invited_by = EXCLUDED.invited_by,
                              created_at = EXCLUDED.created_at,
                              expires_at = EXCLUDED.expires_at
                """)
                .param("skillId", skillId)
                .param("inviteeId", inviteeId)
                .param("role", role)
                .param("invitedBy", invitedBy)
                .param("at", at)
                .param("expiresAt", expiresAt)
                .update();
    }

    /** @return the row that was removed, or empty when there was none */
    public Optional<SkillInvitation> delete(String skillId, String inviteeId) {
        return jdbc.sql("DELETE FROM skill_invitation"
                        + " WHERE skill_id = :skillId AND invitee_id = :inviteeId"
                        + " RETURNING " + COLUMNS)
                .param("skillId", skillId)
                .param("inviteeId", inviteeId)
                .query(SkillInvitationRepository::map)
                .optional();
    }

    /**
     * Every invitation row on this skill, newest first — expired ones included.
     *
     * <p>The inviter's page shows both groups; whether a row is still acceptable is computed from
     * {@code expires_at} at read time (ADR 0138 §决定 4), not stored or filtered here.
     */
    public List<SkillInvitation> allOf(String skillId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill_invitation"
                        + " WHERE skill_id = :skillId"
                        + " ORDER BY created_at DESC, invitee_id ASC")
                .param("skillId", skillId)
                .query(SkillInvitationRepository::map)
                .list();
    }

    private static SkillInvitation map(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new SkillInvitation(
                rs.getString("skill_id"),
                rs.getString("invitee_id"),
                rs.getString("role"),
                rs.getString("invited_by"),
                rs.getString("created_at"),
                rs.getString("expires_at"));
    }
}
