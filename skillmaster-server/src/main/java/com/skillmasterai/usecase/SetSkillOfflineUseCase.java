package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stops serving a whole skill, and resumes it.
 *
 * <p>A service switch, not a version state: no {@code skill_version} row changes and the pointer
 * stays where it is. Every consumer read answers 503 {@code skill_offline} while it is off, and
 * restoring needs no re-publish. Only the owner may set it — {@code offline} takes the skill away
 * from every caller at once.
 */
@Component
public class SetSkillOfflineUseCase {

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final AuditLog audit;

    public SetSkillOfflineUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            AuditLog audit) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.audit = audit;
    }

    /**
     * @param offline true to stop serving, false to resume
     * @return the skill id, or empty when the address resolves to nothing
     */
    @Transactional
    public Optional<String> set(String namespaceSlug, String name, boolean offline,
            AuthenticatedSubject subject) {
        Optional<Access> access = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.accessFor(namespace.id(), name,
                        Callers.of(namespaces, subject)));
        if (access.isEmpty()) {
            return Optional.empty();
        }
        if (!access.get().owns()) {
            throw new NotPermittedException("switching '" + name
                    + "' off is the owner's; it stops every caller, not just some of them");
        }
        String skillId = access.get().skillId();
        // Idempotent, and idempotent all the way down: the same switch reached twice writes nothing
        // and audits nothing (TD §skill 级服务开关). Writing again would also move the moment the
        // skill went offline, which is a fact the page shows.
        boolean currentlyOffline = access.get().offlineAt() != null;
        if (currentlyOffline == offline) {
            return Optional.of(skillId);
        }
        metadata.setOffline(skillId, offline ? com.skillmasterai.common.Timestamps.now() : null);
        audit.record(new AuditEvent(subject.userId(), offline ? "skill_offline" : "skill_online",
                "skill", skillId, Map.of("name", name)));
        return Optional.of(skillId);
    }
}
