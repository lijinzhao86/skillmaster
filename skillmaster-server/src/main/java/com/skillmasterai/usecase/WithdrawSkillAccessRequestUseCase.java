package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.notification.NotificationKinds;
import com.skillmasterai.modules.notification.NotificationOutcome;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.skill.SkillAccessService;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdraws the caller's own pending request (ADR 0052, v45).
 *
 * <p>Idempotent in the same way withdrawing a share is: "I am not waiting" is a state that can be
 * reached again. When a row was actually removed, the approvers' notification is closed as
 * withdrawn; when nothing was pending there is nothing to close.
 */
@Component
public class WithdrawSkillAccessRequestUseCase {

    private final NamespaceService namespaces;
    private final SkillAccessService access;
    private final NotificationService notifications;
    private final AuditLog audit;

    public WithdrawSkillAccessRequestUseCase(NamespaceService namespaces, SkillAccessService access,
            NotificationService notifications, AuditLog audit) {
        this.namespaces = namespaces;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
    }

    /** @return the skill's id, or empty when the caller cannot see it — §4.1's one 404 */
    @Transactional
    public Optional<String> withdraw(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        Optional<String> skillId = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> access.withdraw(namespace.id(), name,
                        Callers.of(namespaces, subject))
                        .map(withdrawal -> {
                            if (withdrawal.removed()) {
                                notifications.resolve(
                                        NotificationSubjects.accessRequest(withdrawal.skillId(),
                                                subject.userId()),
                                        NotificationKinds.ACCESS_REQUEST,
                                        NotificationOutcome.WITHDRAWN,
                                        Timestamps.now());
                            }
                            // Audited whether or not a row was there, for the reason the unshare
                            // audit gives: "somebody asked to stop waiting" is a fact either way.
                            audit.record(new AuditEvent(subject.userId(), "withdraw", "skill",
                                    withdrawal.skillId(), Map.of("removed", withdrawal.removed())));
                            return withdrawal.skillId();
                        }));

        return skillId;
    }
}
