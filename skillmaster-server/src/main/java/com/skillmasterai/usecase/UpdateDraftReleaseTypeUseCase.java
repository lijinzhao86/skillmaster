package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.ReleaseType;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets or clears the release type a draft declares (ADR 0091).
 *
 * <p>Unlike the name and the note this is not the submitter's alone: the type changes nothing that
 * is visible, it is an input to the next publish, and the rung that decides is {@code write}.
 *
 * <p>{@code none} clears it rather than storing the word: undeclared and "explicitly nothing" are
 * the same state, and a second spelling for it would be a second thing to read.
 */
@Component
public class UpdateDraftReleaseTypeUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final SkillMetadataService metadata;
    private final AuditLog audit;

    public UpdateDraftReleaseTypeUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, AuditLog audit) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.metadata = metadata;
        this.audit = audit;
    }

    /**
     * @param releaseType {@code major} / {@code minor} / {@code patch} / {@code none}, or null
     * @return the skill id when the pin named a version of this skill
     */
    @Transactional
    public Optional<String> setReleaseType(String namespaceSlug, String name, VersionPin pin,
            String releaseType, AuthenticatedSubject subject) {
        String canonical = canonical(releaseType);
        Optional<String> skillId = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.accessFor(namespace.id(), name,
                                Callers.of(namespaces, subject))
                        .flatMap(access -> versions.updateDraftReleaseType(access, pin, canonical)));
        skillId.ifPresent(id -> {
            Map<String, Object> facts = new HashMap<>();
            facts.put("name", name);
            facts.put("version", pin.suffix().orElseThrow());
            // **Absence is how a cleared type is recorded.** {@code AuditEvent} copies the map with
            // {@code Map.copyOf}, which refuses nulls — and the row would gain nothing from a
            // placeholder: the action itself says a type was set, and no {@code release_type} says
            // there is none. Same reading as the column: NULL means undeclared.
            if (canonical != null) {
                facts.put("release_type", canonical);
            }
            audit.record(new AuditEvent(subject.userId(), "release_type_draft", "skill", id, facts));
        });
        return skillId;
    }

    /**
     * The wire spelling, with {@code none} folded to null.
     *
     * @throws com.skillmasterai.common.RequestException when the value names no type — a 400 that
     *         says which field, so the CLI can print what to fix
     */
    private static String canonical(String releaseType) {
        if (releaseType != null && "none".equalsIgnoreCase(releaseType.trim())) {
            return null;
        }
        return ReleaseType.canonicalOrNull(releaseType);
    }
}
