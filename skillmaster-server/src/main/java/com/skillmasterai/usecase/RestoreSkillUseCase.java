package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.NotPermittedException;
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

    /** @return the restored row, or empty when there was nothing the caller may restore */
    @Transactional
    public Optional<SkillMetadataService.Restored> restore(String namespaceSlug, String name,
            String id, AuthenticatedSubject subject) {
        Optional<SkillMetadataService.Restored> restored = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.restore(namespace.id(), name, id,
                        Callers.of(namespaces, subject), Timestamps.now()));

        // Inside the transaction, for the reason the delete's audit row is: a trace that outlives
        // a rollback describes something that never happened.
        restored.ifPresent(row -> audit.record(
                AuditEvent.of(subject.userId(), "restore", "skill", row.id())));
        return restored;
    }

    /**
     * The API plane's restore: the same action, with the three answers a client can act on.
     *
     * <p><strong>The tombstone is tried first, because a live skill and a tombstone can share a
     * name since v61.</strong> That is exactly the case the rename was built for: delete, create
     * the name again, then restore the old row — which comes back as
     * {@code 原名-restored-<id>}. Only when there is no tombstone does a visible live skill become
     * the 409 "nothing to restore"; one the caller cannot see is the ordinary 404.
     *
     * <p>A live skill the caller can see but does not own is a 403 — restoring is the owner's
     * alone, and a 404 would send them to check an address that is right.
     *
     * @return the restored row, or empty when the address names nothing the caller may touch
     * @throws com.skillmasterai.common.ConflictException {@code skill_not_deleted} for a live skill
     * @throws NotPermittedException for a live skill owned by somebody else
     */
    @Transactional
    public Optional<SkillMetadataService.Restored> restoreFromApi(String namespaceSlug, String name,
            String id, AuthenticatedSubject subject) {
        Optional<com.skillmasterai.modules.namespace.Namespace> namespace =
                namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        Optional<SkillMetadataService.Restored> restored =
                restore(namespaceSlug, name, id, subject);
        if (restored.isPresent()) {
            return restored;
        }
        Caller caller = Callers.of(namespaces, subject);
        Optional<Access> live = metadata.accessFor(namespace.get().id(), name, caller);
        if (live.isPresent()) {
            if (!live.get().owns()) {
                throw new NotPermittedException(
                        "restoring '" + name + "' is its owner's to do");
            }
            throw new com.skillmasterai.common.ConflictException(
                    com.skillmasterai.common.ErrorCode.SKILL_NOT_DELETED,
                    "'" + name + "' is not deleted; there is nothing to restore");
        }
        return Optional.empty();
    }
}
