package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.SkillVersionService;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Soft-deletes one skill.
 *
 * <p>Deleting something that is not yours and deleting something that does not exist produce the
 * same answer — nothing, which the controller renders as §4.1's 404 — and that is deliberate. §4.2
 * requires an unauthorized read of a private skill to be a 404 rather than a 403 because a 403
 * confirms the skill exists; the same reasoning applies to a delete, where the leak would be just
 * as good.
 *
 * <p>Two things hold that equivalence up, and neither is a comparison of two lookups. The address's
 * namespace segment has to be the caller's own ({@link NamespaceService#readableNamespaceOf}), and
 * the name is then resolved inside that namespace by an {@code UPDATE} that carries the namespace
 * predicate — so there is no window between deciding and acting, and no second branch that could be
 * edited into a 403.
 *
 * <p>The audit row names the skill by its id rather than by the address it was reached through: the
 * id is the identity (ADR 0004), so a rename cannot orphan the trail. That is also why the deletion
 * reports which skill it removed instead of merely whether it removed one.
 */
@Component
public class SoftDeleteSkillUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final AuditLog audit;

    public SoftDeleteSkillUseCase(NamespaceService namespaces, SkillVersionService versions,
            AuditLog audit) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.audit = audit;
    }

    /** @return the deleted skill's id, or empty when there was nothing the caller may delete */
    @Transactional
    public Optional<String> softDelete(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        Optional<String> deleted = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> versions.softDelete(namespace.id(), name,
                        Callers.of(namespaces, subject)));

        // Inside the transaction on purpose: a trace that can outlive the rollback of the change it
        // describes reads as evidence of something that never happened.
        deleted.ifPresent(id -> audit.record(AuditEvent.of(subject.userId(), "delete", "skill", id)));
        return deleted;
    }
}
