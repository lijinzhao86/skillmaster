package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.SkillVersionService;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brings a soft-deleted skill back (ADR 0050).
 *
 * <p>The mirror of {@link SoftDeleteSkillUseCase}, and deliberately the same shape: the resolution
 * happens inside M7 against the address and the caller's ownership, so "not yours" and "no such
 * deleted skill" are one empty result and the controller renders §4.1's 404. A deleted skill has to
 * stay indistinguishable from one that never existed — that is what deletion's address rule is for.
 *
 * <p><strong>Restore is the owner's alone.</strong> It is not reachable through any grantable rung,
 * which is why this path asks ownership and not {@code admin} (ADR 0047 §决定 4).
 */
@Component
public class RestoreSkillUseCase {

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final AuditLog audit;

    public RestoreSkillUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            AuditLog audit) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.audit = audit;
    }

    /** @return the restored skill's id, or empty when there was nothing the caller may restore */
    @Transactional
    public Optional<String> restore(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        Optional<String> restored = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.restore(namespace.id(), name,
                        Callers.of(namespaces, subject), Timestamps.now()));

        // Inside the transaction, for the reason the delete's audit row is: a trace that outlives
        // a rollback describes something that never happened.
        restored.ifPresent(id -> audit.record(AuditEvent.of(subject.userId(), "restore", "skill", id)));
        return restored;
    }
}
