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

/** Declines a share invitation: nothing is granted, the invitation row goes away (v45). */
@Component
public class DeclineSkillInvitationUseCase {

    private final NotificationService notifications;
    private final SkillInvitationService invitations;
    private final AuditLog audit;

    public DeclineSkillInvitationUseCase(NotificationService notifications,
            SkillInvitationService invitations, AuditLog audit) {
        this.notifications = notifications;
        this.invitations = invitations;
        this.audit = audit;
    }

    @Transactional
    public void decline(String notificationId, AuthenticatedSubject subject) {
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
        SkillInvitationService.Declined declined = invitations
                .decline(row.skillId(), subject.userId(), at)
                .orElseThrow(() -> new NotificationStateException("already_resolved",
                        "this invitation is no longer waiting"));

        // Declining an already-expired invitation still removes it, but the archive must say what
        // happened: the row expired, it was not answered.
        notifications.resolve(row.subjectKey(), NotificationKinds.SHARE_INVITATION,
                declined.expired() ? NotificationOutcome.EXPIRED : NotificationOutcome.DECLINED, at);
        // Answering a notification is reading it: the design's 「打开即已读」 covers 接受 / 拒绝 too.
        notifications.markRead(row.id(), subject.userId(), Set.of(), at);
        audit.record(new AuditEvent(subject.userId(), "decline_invitation", "skill",
                declined.skillId(), Map.of("role", declined.role(),
                        "invited_by", declined.invitedBy(),
                        "expired", declined.expired())));
    }
}
