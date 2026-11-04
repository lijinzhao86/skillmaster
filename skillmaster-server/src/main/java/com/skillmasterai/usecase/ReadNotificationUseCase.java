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

/** One notification, resolved for the caller's current standing. */
@Component
public class ReadNotificationUseCase {

    private final NamespaceService namespaces;
    private final SkillAccessService access;
    private final NotificationService notifications;
    private final NotificationViewAssembler assembler;

    public ReadNotificationUseCase(NamespaceService namespaces, SkillAccessService access,
            NotificationService notifications, NotificationViewAssembler assembler) {
        this.namespaces = namespaces;
        this.access = access;
        this.notifications = notifications;
        this.assembler = assembler;
    }

    @Transactional(readOnly = true)
    public NotificationView show(String id, AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        String now = Timestamps.now();
        return notifications.findFor(id, subject.userId(), access.administeredSkillIds(caller))
                .map(row -> assembler.assemble(row, caller, now))
                .orElseThrow(NotificationNotFoundException::new);
    }
}
