package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.Caller;
import com.skillmasterai.modules.version.SkillSharingService;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdrawing a skill's share (ADR 0034). The mirror of {@link ShareSkillUseCase}, and deliberately
 * not its inverse in one respect: withdrawing a grant that is not there is a success.
 */
@Component
public class RevokeSkillShareUseCase {

    private final NamespaceService namespaces;
    private final AccountDirectory accounts;
    private final SkillSharingService sharing;
    private final AuditLog audit;

    public RevokeSkillShareUseCase(NamespaceService namespaces, AccountDirectory accounts,
            SkillSharingService sharing, AuditLog audit) {
        this.namespaces = namespaces;
        this.accounts = accounts;
        this.sharing = sharing;
        this.audit = audit;
    }

    /**
     * <p><strong>Idempotent, unlike every other "nothing there" in this system.</strong> The request
     * says "this person must no longer have access", and that state already holds — so answering 404
     * would tell the caller their handle was wrong when it was right, and answering 409 would make a
     * retried request look like a failure. It is a state that converges, not a row that is deleted.
     *
     * @return the skill and whether a grant was actually removed, or empty when there is no such
     *         skill for this caller
     * @throws AccountRequestException when the handle names nobody, because that one really is the
     *         caller's to fix and silently succeeding would hide a typo
     */
    @Transactional
    public Optional<Revoked> revoke(String namespaceSlug, String name, String handle,
            AuthenticatedSubject subject) {
        String granteeId = accounts.userIdOf(handle)
                .orElseThrow(() -> new AccountRequestException("handle", "no_such_user"));

        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        Caller caller = new Caller(subject.userId(), namespaces
                .personalNamespaceOf(subject.userId()).id());

        return sharing.revoke(namespace.get().id(), name, granteeId, caller).map(revocation -> {
            // Audited whether or not a row was there. "Somebody asked for this person to lose
            // access" is a fact worth keeping even when it turned out they already had none, and the
            // removed role tells the two apart when the trail is read later.
            audit.record(new AuditEvent(subject.userId(), "unshare", "skill", revocation.skillId(),
                    Map.of("grantee", handle, "role", revocation.removedRole().orElse("none"))));
            return new Revoked(revocation.skillId(), handle, revocation.removedRole().isPresent());
        });
    }

    /** @param removed false when nothing was shared with that account to begin with */
    public record Revoked(String skillId, String handle, boolean removed) {
    }
}
