package com.skillmasterai.modules.skill;

/**
 * A share waiting to be accepted.
 *
 * <p><strong>The row's presence is the state.</strong> There is no {@code status} and no
 * {@code accepted_at}: accepting writes {@code skill_grant} and deletes the row, declining or
 * revoking deletes it, and expiry is {@code expires_at <= now} — the same shape
 * {@code skill_access_request} has (ADR 0138).
 */
public record SkillInvitation(
        String skillId,
        String inviteeId,
        String role,
        String invitedBy,
        String createdAt,
        String expiresAt) {

    public boolean expiredAt(String now) {
        return expiresAt.compareTo(now) <= 0;
    }
}
