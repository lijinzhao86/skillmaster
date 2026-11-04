package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.notification.Notification;
import com.skillmasterai.modules.notification.NotificationKinds;
import com.skillmasterai.modules.notification.NotificationNotFoundException;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.notification.NotificationStateException;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillAccessService;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approves or denies a request from the notification the approver holds (v45, ADR 0140).
 *
 * <p>The CLI's entry. It resolves the notification — which names the skill and the requester by
 * identity — and then calls the same decision the console's collaborators pane calls. The two
 * planes therefore cannot produce different grants, different notifications or different audit
 * rows.
 */
@Component
public class ApproveAccessRequestByNotificationUseCase {

    private final NamespaceService namespaces;
    private final SkillAccessService access;
    private final NotificationService notifications;
    private final DecideSkillAccessRequestUseCase decide;

    public ApproveAccessRequestByNotificationUseCase(NamespaceService namespaces,
            SkillAccessService access, NotificationService notifications,
            DecideSkillAccessRequestUseCase decide) {
        this.namespaces = namespaces;
        this.access = access;
        this.notifications = notifications;
        this.decide = decide;
    }

    /**
     * @param role     the rung to grant when approving; null means the one that was requested
     * @param approve  true grants and closes the request; false only closes it
     * @throws NotificationNotFoundException when the row is not this caller's or is no longer visible
     * @throws NotificationStateException for the wrong kind, or an already answered request
     */
    @Transactional
    public DecideSkillAccessRequestUseCase.Decision decide(String notificationId, String role,
            boolean approve, AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        Set<String> administered = access.administeredSkillIds(caller);
        Notification row = notifications.findFor(notificationId, subject.userId(), administered)
                .orElseThrow(NotificationNotFoundException::new);
        if (!NotificationKinds.ACCESS_REQUEST.equals(row.kind())) {
            throw new NotificationStateException("not_an_access_request",
                    "this notification is not an access request");
        }
        if (row.outcome() != null) {
            throw new NotificationStateException("already_resolved",
                    "this request has already been answered");
        }

        String approvedRole = approve ? (role == null ? row.role() : role) : null;
        DecideSkillAccessRequestUseCase.Decision decision =
                decide.decideBySkillId(row.skillId(), row.actorId(), approvedRole, approve, subject)
                        .orElseThrow(() -> new NotificationStateException("already_resolved",
                                "this request is no longer pending"));
        // Approving or denying from the notification is handling it; it must not stay unread.
        notifications.markRead(row.id(), subject.userId(), administered, Timestamps.now());
        return decision;
    }
}
