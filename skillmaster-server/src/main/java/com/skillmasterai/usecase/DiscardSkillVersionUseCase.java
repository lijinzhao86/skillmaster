package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.SkillVersionService;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Throws away a draft nobody intends to publish.
 *
 * <p>Not a deletion: the row and its bytes stay, and a discarded version simply stops being offered
 * anywhere (ADR 0031). That keeps this consistent with the rest of v1, which cannot remove a version
 * at all, and it keeps the audit trail of the submission intact — the point of discarding is that
 * the author changed their mind, which is a thing worth being able to see.
 *
 * <p>Only a draft can be discarded, and only its own author can reach it: the namespace gate is the
 * same one every read uses.
 */
@Component
public class DiscardSkillVersionUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final AuditLog audit;

    public DiscardSkillVersionUseCase(NamespaceService namespaces, SkillVersionService versions,
            AuditLog audit) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.audit = audit;
    }

    /**
     * @return the id of the skill the version belonged to, or empty when the address resolves to
     *         nothing the caller may act on
     * @throws com.skillmasterai.modules.version.VersionStateException when the version is not a
     *         draft — including when it is already published, which no longer may be taken away
     */
    @Transactional
    public Optional<String> discard(String namespaceSlug, String name, int number,
            AuthenticatedSubject subject) {
        Optional<String> skillId = namespaces.readableNamespaceOf(subject.userId(), namespaceSlug)
                .flatMap(namespace -> versions.discardVersion(namespace.id(), name, number));

        // Inside the transaction, like every other audit row here: a trace that can outlive the
        // rollback of the change it describes reads as evidence of something that never happened.
        skillId.ifPresent(id -> audit.record(new AuditEvent(subject.userId(), "discard", "skill", id,
                Map.of("name", name, "number", number))));
        return skillId;
    }
}
