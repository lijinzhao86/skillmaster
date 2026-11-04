package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillAccessService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** "Mark all read", scoped to what this account can currently see. */
@Component
public class MarkAllNotificationsReadUseCase {

    private final NamespaceService namespaces;
    private final SkillAccessService access;
    private final NotificationService notifications;

    public MarkAllNotificationsReadUseCase(NamespaceService namespaces, SkillAccessService access,
            NotificationService notifications) {
        this.namespaces = namespaces;
        this.access = access;
        this.notifications = notifications;
    }

    /** @return how many rows were newly marked */
    @Transactional
    public int markAll(AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        return notifications.markAllRead(subject.userId(),
                access.administeredSkillIds(caller), Timestamps.now());
    }
}
