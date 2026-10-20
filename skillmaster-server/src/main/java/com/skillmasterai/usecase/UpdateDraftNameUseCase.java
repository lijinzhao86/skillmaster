package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renames a draft (ADR 0055).
 *
 * <p>The name is the author's label for a version, not its identity, so changing it moves no
 * address and no digest. Only a draft may be renamed; after publish it is frozen.
 */
@Component
public class UpdateDraftNameUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final SkillMetadataService metadata;
    private final AuditLog audit;

    public UpdateDraftNameUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, AuditLog audit) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.metadata = metadata;
        this.audit = audit;
    }

    @Transactional
    public Optional<String> rename(String namespaceSlug, String name, VersionPin pin,
            String draftName, AuthenticatedSubject subject) {
        String checked = VersionLabels.name(draftName);
        Optional<String> skillId = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.accessFor(namespace.id(), name,
                                Callers.of(namespaces, subject))
                        .flatMap(access -> versions.updateDraftName(access, pin, checked)));
        skillId.ifPresent(id -> audit.record(new AuditEvent(subject.userId(), "rename_draft",
                "skill", id, Map.of("name", name, "version", pin.suffix().orElseThrow()))));
        return skillId;
    }
}
