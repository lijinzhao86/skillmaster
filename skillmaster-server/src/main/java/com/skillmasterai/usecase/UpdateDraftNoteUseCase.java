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
 * Replaces a draft's one-line note (ADR 0055).
 *
 * <p>Full replacement rather than an append: the note says what this version is, and two sentences
 * accumulated over two edits would stop being one statement. Frozen once the version is published.
 */
@Component
public class UpdateDraftNoteUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final SkillMetadataService metadata;
    private final AuditLog audit;

    public UpdateDraftNoteUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, AuditLog audit) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.metadata = metadata;
        this.audit = audit;
    }

    @Transactional
    public Optional<String> setNote(String namespaceSlug, String name, VersionPin pin,
            String note, AuthenticatedSubject subject) {
        String checked = VersionLabels.note(note);
        Optional<String> skillId = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.accessFor(namespace.id(), name,
                                Callers.of(namespaces, subject))
                        .flatMap(access -> versions.updateDraftNote(access, pin, checked)));
        skillId.ifPresent(id -> audit.record(new AuditEvent(subject.userId(), "note_draft",
                "skill", id, Map.of("name", name, "version", pin.suffix().orElseThrow()))));
        return skillId;
    }
}
