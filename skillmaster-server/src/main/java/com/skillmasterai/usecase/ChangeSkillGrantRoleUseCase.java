package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillSharingService;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves an existing collaborator to a lower rung (v45).
 *
 * <p>v45 removed the direct grant route, so raising somebody now goes through an invitation they
 * accept. Lowering is the other direction: it gives nothing new and needs no consent, which is why
 * it stays a direct, audited action. The controller answers 404 when the person has no grant —
 * there is no row to move, and "invite them" is a different action.
 */
@Component
public class ChangeSkillGrantRoleUseCase {

    private final NamespaceService namespaces;
    private final AccountDirectory accounts;
    private final SkillSharingService sharing;
    private final AuditLog audit;

    public ChangeSkillGrantRoleUseCase(NamespaceService namespaces, AccountDirectory accounts,
            SkillSharingService sharing, AuditLog audit) {
        this.namespaces = namespaces;
        this.accounts = accounts;
        this.sharing = sharing;
        this.audit = audit;
    }

    @Transactional
    public Optional<Result> change(String namespaceSlug, String name, String handle, String role,
            AuthenticatedSubject subject) {
        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> granteeId = accounts.userIdOf(handle);
        if (granteeId.isEmpty()) {
            return Optional.empty();
        }
        Caller caller = Callers.of(namespaces, subject);
        String at = Timestamps.now();
        Optional<SkillSharingService.RoleChange> changed = sharing.changeRole(
                namespace.get().id(), name, granteeId.get(), role, caller, at);
        changed.ifPresent(change -> audit.record(new AuditEvent(subject.userId(),
                "change_role", "skill", change.skillId(),
                Map.of("grantee", handle, "from", change.from(), "to", change.to()))));
        return changed.map(change -> new Result(handle, change.to(), change.changed()));
    }

    public record Result(String handle, String role, boolean changed) {
    }
}
