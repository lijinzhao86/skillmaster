package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.notification.NotificationNotFoundException;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillAccessService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks one notification read.
 *
 * <p><strong>Read is not handled.</strong> This changes the badge and nothing about the invitation
 * or request; that distinction is the whole of ADR 0139's state model.
 */
@Component
public class MarkNotificationReadUseCase {

    private final NamespaceService namespaces;
    private final SkillAccessService access;
    private final NotificationService notifications;

    public MarkNotificationReadUseCase(NamespaceService namespaces, SkillAccessService access,
            NotificationService notifications) {
        this.namespaces = namespaces;
        this.access = access;
        this.notifications = notifications;
    }

    @Transactional
    public void markRead(String id, AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        boolean marked = notifications.markRead(id, subject.userId(),
                access.administeredSkillIds(caller), Timestamps.now());
        if (!marked) {
            // Covers three cases with one answer: no such id, somebody else's, and a management
            // notification the caller may no longer see. A distinct 404 for the third would say
            // that the row exists.
            throw new NotificationNotFoundException();
        }
    }
}
