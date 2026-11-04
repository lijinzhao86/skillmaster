package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.notification.NotificationDraft;
import com.skillmasterai.modules.notification.NotificationKinds;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.skill.SkillAccessService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks for a rung on a skill the caller can see (ADR 0052, v45).
 *
 * <p>The request is still an object of its own and grants nothing. What v45 adds is that everyone
 * who can answer it — the owner and every {@code admin} grantee — is told once, through the same
 * notification floor every other event uses.
 */
@Component
public class RequestSkillAccessUseCase {

    private final NamespaceService namespaces;
    private final AccountDirectory accounts;
    private final SkillAccessService access;
    private final NotificationService notifications;
    private final AuditLog audit;

    public RequestSkillAccessUseCase(NamespaceService namespaces, AccountDirectory accounts,
            SkillAccessService access, NotificationService notifications, AuditLog audit) {
        this.namespaces = namespaces;
        this.accounts = accounts;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
    }

    /** @return the skill's id, or empty when the caller cannot see it — §4.1's one 404 */
    @Transactional
    public Optional<String> request(String namespaceSlug, String name, String rung,
            AuthenticatedSubject subject) {
        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        String at = Timestamps.now();
        Optional<String> skillId = access.request(namespace.get().id(), name, rung,
                Callers.of(namespaces, subject), at);
        if (skillId.isEmpty()) {
            return Optional.empty();
        }

        String id = skillId.get();
        Set<String> recipients = access.adminUserIds(id, namespace.get().ownerUserId());
        String actorHandle = accounts.handleOf(subject.userId()).orElse(null);
        notifications.emit(new NotificationDraft(
                NotificationKinds.ACCESS_REQUEST,
                NotificationSubjects.accessRequest(id, subject.userId()),
                subject.userId(),
                actorHandle,
                id,
                rung,
                null,
                null,
                null,
                List.copyOf(recipients)), at);

        audit.record(new AuditEvent(subject.userId(), "request", "skill", id,
                Map.of("rung", rung)));
        return skillId;
    }
}
