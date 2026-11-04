package com.skillmasterai.modules.notification;

import java.util.List;

/**
 * What a caller hands M14 to create a notification.
 *
 * <p><strong>No business words.</strong> {@code kind} is an open string, {@code subjectKey} is the
 * caller's convention, and the recipients are user ids the use case already resolved. That is what
 * lets M14 stay independent: it does not know what an invitation or a skill is, and a new kind is
 * a new string rather than a change here.
 *
 * @param subjectKey non-null for request-shaped notifications; null for announcements. Two open
 *                   notifications of the same kind and subject are one row (the repository upserts).
 * @param expiresAt  only invitations carry one
 */
public record NotificationDraft(
        String kind,
        String subjectKey,
        String actorId,
        String actorHandle,
        String skillId,
        String role,
        String title,
        String body,
        String expiresAt,
        List<String> recipients) {

    public NotificationDraft {
        recipients = List.copyOf(recipients);
    }
}
