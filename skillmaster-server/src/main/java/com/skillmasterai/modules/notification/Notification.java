package com.skillmasterai.modules.notification;

/**
 * One notification, as M14 stores it.
 *
 * <p>{@code outcome} is a result snapshot, not the business state: whether an invitation can still
 * be accepted is answered by {@code skill_invitation}, and this column only remembers what happened
 * when it stopped being answerable. {@code read} belongs to the recipient, not to the event.
 *
 * @param actorHandle the actor's handle at write time, kept as history
 * @param expiresAt   only invitations carry one; null elsewhere
 * @param read        this recipient's state
 */
public record Notification(
        String id,
        String kind,
        String subjectKey,
        String actorId,
        String actorHandle,
        String skillId,
        String role,
        String outcome,
        String expiresAt,
        String title,
        String body,
        String createdAt,
        String resolvedAt,
        boolean read) {
}
