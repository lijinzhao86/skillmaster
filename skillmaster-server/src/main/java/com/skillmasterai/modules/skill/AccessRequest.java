package com.skillmasterai.modules.skill;

/**
 * One pending request for a rung on one skill (ADR 0052).
 *
 * <p>Public because it is what {@link SkillAccessService} hands back, and the seam may not be
 * spelled in the module's internals.
 *
 * @param requesterId the account asking, still an id — turning it into a handle is M1's, one layer
 *                    up where {@code app_user} can be read
 * @param wantedRole  the rung they asked for. The approver may grant a different one; that rung
 *                    lives in {@code skill_grant}, and this value only survives in the audit row
 * @param createdAt   RFC3339 UTC, moved when the requester resubmits (resubmitting is an upsert)
 */
public record AccessRequest(String requesterId, String wantedRole, String createdAt) {
}
