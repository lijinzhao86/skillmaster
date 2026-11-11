package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SkillMetadataService.DeleteResult;
import com.skillmasterai.modules.version.SkillVersionService;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Soft-deletes a skill that has never had a version go live (v37, ADR 0127).
 *
 * <p>Its own use case rather than a branch of {@link SoftDeleteSkillUseCase}: that one serves the
 * browser, where a person confirms the delete and a published skill is deletable; this one serves a
 * credential, where only the never-published ones are. Two callers, two rules — folding them
 * together would put "was it confirmed in a browser?" into the delete itself.
 *
 * <p>The audit row is written inside the transaction for the same reason as the browser path: a
 * trace that can outlive the rollback of the change it describes reads as evidence of something that
 * never happened. It names the skill by id, which is the identity (ADR 0004).
 */
@Component
public class DeleteNeverPublishedSkillUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final SkillMetadataService metadata;
    private final AuditLog audit;
    private final SkillLifecycleNotices notices;
    private final AccountDirectory accounts;

    public DeleteNeverPublishedSkillUseCase(NamespaceService namespaces,
            SkillVersionService versions, SkillMetadataService metadata, AuditLog audit,
            SkillLifecycleNotices notices, AccountDirectory accounts) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.metadata = metadata;
        this.audit = audit;
        this.notices = notices;
        this.accounts = accounts;
    }

    @Transactional
    public SkillMetadataService.DeleteOutcome delete(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        // The delete writes a metadata row; the sweep it must share a transaction with is M7's
        // (ADR 0053), so the same exclusive-write wrapper the browser path uses is taken here.
        String at = Timestamps.now();
        Optional<DeleteResult> result = namespaces.bySlug(namespaceSlug)
                .map(namespace -> versions.underExclusiveWrite(() -> metadata.deleteNeverPublished(
                        namespace.id(), name, Callers.of(namespaces, subject), at)));

        result.filter(attempt -> attempt.outcome() == SkillMetadataService.DeleteOutcome.DELETED)
                .ifPresent(attempt -> {
                    audit.record(
                            AuditEvent.of(subject.userId(), "delete", "skill", attempt.skillId()));
                    // v61 (ADR 0184): a never-published skill can still be shared, and A11 is the
                    // same promise for it — whoever held access hears that it is gone.
                    notices.deleted(attempt.skillId(), subject.userId(),
                            accounts.handleOf(subject.userId()).orElse(null),
                            namespaceSlug + "/" + name, at);
                });
        return result.map(DeleteResult::outcome)
                .orElse(SkillMetadataService.DeleteOutcome.NOT_FOUND);
    }
}
