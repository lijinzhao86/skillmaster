package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillAccessService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The one number the top bar asks for on mount. */
@Component
public class UnreadNotificationCountUseCase {

    private final NamespaceService namespaces;
    private final SkillAccessService access;
    private final NotificationService notifications;

    public UnreadNotificationCountUseCase(NamespaceService namespaces, SkillAccessService access,
            NotificationService notifications) {
        this.namespaces = namespaces;
        this.access = access;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public long unread(AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        return notifications.unread(subject.userId(), access.administeredSkillIds(caller));
    }
}
