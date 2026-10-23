package com.skillmasterai.usecase;

import com.skillmasterai.common.ConflictException;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillLibraryService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removing the caller's own join reference (v14, ADR 0082).
 *
 * <p><strong>The judgement is "is there a row", never "what source is displayed".</strong> A person
 * can join a skill and later be granted it: the displayed source becomes "shared", the join row is
 * still theirs, and leaving still removes that row. Using the displayed source here would tell them
 * they never joined something they did.
 *
 * <p>A skill with no join row is a 409 rather than a silent success: reporting success would confirm
 * a removal that did not happen.
 *
 * <p><strong>A dormant entry can still be left.</strong> When the skill itself is no longer visible
 * (soft-deleted, or made private without a grant), the normal resolution answers 404 — but the
 * caller's own reference outlives visibility, and PRD #44 says leaving applies to every entry with a
 * reference, dormant ones included. The fallback therefore looks the reference up by address: it
 * only answers for a row the caller owns, so it reveals nothing about a skill they never joined.
 */
@Component
public class LeaveSkillLibraryUseCase {

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final SkillLibraryService library;
    private final AuditLog audit;

    public LeaveSkillLibraryUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            SkillLibraryService library, AuditLog audit) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.library = library;
        this.audit = audit;
    }

    /**
     * @return empty when the address names nothing the caller may see (the controller's 404);
     *         otherwise true — leaving has no no-op: a missing reference is a 409
     */
    @Transactional
    public Optional<Boolean> leave(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        Optional<Organize.Target> resolved =
                Organize.resolve(namespaces, metadata, namespaceSlug, name, subject);
        String skillId;
        if (resolved.isPresent()) {
            skillId = resolved.get().standing().skillId();
        } else {
            // Not visible, but the caller's own reference may still be there (a dormant entry).
            skillId = namespaces.bySlug(namespaceSlug)
                    .flatMap(namespace -> library.joinedSkillId(
                            subject.userId(), namespace.id(), name))
                    .orElse(null);
            if (skillId == null) {
                return Optional.empty();
            }
        }
        if (!library.leave(subject.userId(), skillId)) {
            throw new ConflictException(ErrorCode.LIBRARY_REFERENCE_MISSING,
                    "'" + name + "' has no join reference to remove; it is in your library for"
                            + " another reason");
        }
        audit.record(AuditEvent.of(subject.userId(), "library_leave", "skill", skillId));
        return Optional.of(true);
    }
}
