package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillAccessService;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "What am I waiting for on this skill?" — the CLI's {@code request --status}.
 *
 * <p>The page learns this from {@code pending_rung} on its author detail; the API plane has no
 * author detail to hang it on, and the question is small enough to have its own read rather than
 * widening another response.
 *
 * <p>The two empties are different facts: an empty <em>outer</em> is §4.1's one 404 (the caller
 * cannot see the skill), an empty <em>inner</em> is "visible, and nothing is pending".
 */
@Component
public class ReadSkillAccessRequestUseCase {

    private final NamespaceService namespaces;
    private final SkillAccessService access;

    public ReadSkillAccessRequestUseCase(NamespaceService namespaces, SkillAccessService access) {
        this.namespaces = namespaces;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public Optional<Optional<String>> pending(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> access.pendingRung(namespace.id(), name,
                        Callers.of(namespaces, subject)));
    }
}
