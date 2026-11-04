package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.notification.Notification;
import com.skillmasterai.modules.notification.NotificationKinds;
import com.skillmasterai.modules.notification.NotificationNotFoundException;
import com.skillmasterai.modules.notification.NotificationOutcome;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.notification.NotificationStateException;
import com.skillmasterai.modules.skill.SkillInvitationService;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts a share invitation (v45, ADR 0138).
 *
 * <p>The notification is the address the client holds; the invitation row is the authority. This
 * use case reads one, writes the other, and closes the notification with the result in the same
 * transaction. An expired invitation is deleted and reported as {@code expired} rather than thrown,
 * because both writes have to commit for the next read to be true.
 */
@Component
public class AcceptSkillInvitationUseCase {

    private final NotificationService notifications;
    private final SkillInvitationService invitations;
    private final AuditLog audit;

    public AcceptSkillInvitationUseCase(NotificationService notifications,
            SkillInvitationService invitations, AuditLog audit) {
        this.notifications = notifications;
        this.invitations = invitations;
        this.audit = audit;
    }

    @Transactional
    public Result accept(String notificationId, AuthenticatedSubject subject) {
        Notification row = notifications.findFor(notificationId, subject.userId(), Set.of())
                .orElseThrow(NotificationNotFoundException::new);
        if (!NotificationKinds.SHARE_INVITATION.equals(row.kind())) {
            throw new NotificationStateException("not_an_invitation",
                    "this notification is not a share invitation");
        }
        if (row.outcome() != null) {
            throw new NotificationStateException("already_resolved",
                    "this invitation has already been answered");
        }

        String at = Timestamps.now();
        String subjectKey = row.subjectKey();
        SkillInvitationService.AcceptOutcome outcome = invitations
                .accept(row.skillId(), subject.userId(), at)
                .orElseThrow(() -> new NotificationStateException("already_resolved",
                        "this invitation is no longer waiting"));

        if (outcome.state() == SkillInvitationService.AcceptOutcome.State.EXPIRED) {
            notifications.resolve(subjectKey, NotificationKinds.SHARE_INVITATION,
                    NotificationOutcome.EXPIRED, at);
            notifications.markRead(row.id(), subject.userId(), Set.of(), at);
            return new Result("expired", outcome.skillId(), outcome.role());
        }
        if (outcome.state() == SkillInvitationService.AcceptOutcome.State.GONE) {
            // The skill was deleted after the invitation was sent; close the row rather than leave a
            // pending invitation pointing at something the invitee cannot open.
            notifications.resolve(subjectKey, NotificationKinds.SHARE_INVITATION,
                    NotificationOutcome.REVOKED, at);
            notifications.markRead(row.id(), subject.userId(), Set.of(), at);
            return new Result("revoked", outcome.skillId(), outcome.role());
        }

        notifications.resolve(subjectKey, NotificationKinds.SHARE_INVITATION,
                NotificationOutcome.ACCEPTED, at);
        // Answering a notification is reading it: the design's 「打开即已读」 covers 接受 / 拒绝 too.
        notifications.markRead(row.id(), subject.userId(), Set.of(), at);
        audit.record(new AuditEvent(subject.userId(), "accept_invitation", "skill",
                outcome.skillId(), Map.of("role", outcome.role(),
                        "invited_by", outcome.invitedBy())));
        return new Result("accepted", outcome.skillId(), outcome.role());
    }

    /** @param state {@code accepted}, {@code expired}, or {@code revoked}; the last two are 409s */
    public record Result(String state, String skillId, String role) {
    }
}
