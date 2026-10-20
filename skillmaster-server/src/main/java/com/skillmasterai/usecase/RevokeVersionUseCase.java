package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes one published, non-current version down (ADR 0058), and puts it back.
 *
 * <p>The row, its files and its history stay; only what callers may read changes. The version list
 * on the consumption plane stops offering it, and a caller holding its pin gets 410 — which is why
 * this, like publishing, is a browser-plane action confirmed by a person (ADR 0054, ADR 0061).
 */
@Component
public class RevokeVersionUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final SkillMetadataService metadata;
    private final AuditLog audit;

    public RevokeVersionUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, AuditLog audit) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.metadata = metadata;
        this.audit = audit;
    }

    /**
     * @param reason optional note, stored on the row and shown to the author plane only (ADR 0059)
     * @return the skill id, or empty when the address resolves to nothing the caller may act on
     */
    @Transactional
    public Optional<String> revoke(String namespaceSlug, String name, VersionPin pin,
            String reason, AuthenticatedSubject subject) {
        String checked = VersionLabels.reason(reason);
        Optional<String> skillId = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.accessFor(namespace.id(), name,
                                Callers.of(namespaces, subject))
                        .flatMap(access -> versions.revokeVersion(access, pin, checked)));

        skillId.ifPresent(id -> {
            Map<String, Object> detail = new HashMap<>();
            detail.put("name", name);
            detail.put("version", pin.suffix().orElseThrow());
            if (checked != null) {
                detail.put("reason", checked);
            }
            audit.record(new AuditEvent(subject.userId(), "version_revoke", "skill", id, detail));
        });
        return skillId;
    }

    /** Restores a taken-down version to readable history. The pointer does not move. */
    @Transactional
    public Optional<String> restore(String namespaceSlug, String name, VersionPin pin,
            AuthenticatedSubject subject) {
        Optional<String> skillId = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.accessFor(namespace.id(), name,
                                Callers.of(namespaces, subject))
                        .flatMap(access -> versions.restoreVersion(access, pin)));

        skillId.ifPresent(id -> audit.record(new AuditEvent(subject.userId(), "version_restore",
                "skill", id, Map.of("name", name, "version", pin.suffix().orElseThrow()))));
        return skillId;
    }
}
