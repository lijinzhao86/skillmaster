package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillAccessService;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks for a rung on a skill the caller can see (ADR 0052).
 *
 * <p>The request is an object of its own and grants nothing; the audit row records that somebody
 * asked, which is the only trace this action leaves until an approver answers. The rung is stored
 * as it was asked for — approval may choose another, and that one lives in the grant.
 */
@Component
public class RequestSkillAccessUseCase {

    private final NamespaceService namespaces;
    private final SkillAccessService access;
    private final AuditLog audit;

    public RequestSkillAccessUseCase(NamespaceService namespaces, SkillAccessService access,
            AuditLog audit) {
        this.namespaces = namespaces;
        this.access = access;
        this.audit = audit;
    }

    /** @return the skill's id, or empty when the caller cannot see it — §4.1's one 404 */
    @Transactional
    public Optional<String> request(String namespaceSlug, String name, String rung,
            AuthenticatedSubject subject) {
        Optional<String> skillId = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> access.request(namespace.id(), name, rung,
                        Callers.of(namespaces, subject), Timestamps.now()));

        skillId.ifPresent(id -> audit.record(new AuditEvent(subject.userId(), "request", "skill", id,
                Map.of("rung", rung))));
        return skillId;
    }
}
