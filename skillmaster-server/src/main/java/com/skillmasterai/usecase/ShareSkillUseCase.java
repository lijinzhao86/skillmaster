package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.Caller;
import com.skillmasterai.modules.version.GrantRole;
import com.skillmasterai.modules.version.SkillSharingService;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sharing one skill with one account (ADR 0034).
 *
 * <p>The composition §2.5 names: M4 resolves the namespace the address names, M1 turns the handle
 * the caller typed into an account, M7 decides whether this caller may share what is there and
 * writes the grant, and M10 records it. Every one of those is a question another module owns the
 * answer to, which is why the meeting is here and not in any of them.
 */
@Component
public class ShareSkillUseCase {

    private final NamespaceService namespaces;
    private final AccountDirectory accounts;
    private final SkillSharingService sharing;
    private final AuditLog audit;

    public ShareSkillUseCase(NamespaceService namespaces, AccountDirectory accounts,
            SkillSharingService sharing, AuditLog audit) {
        this.namespaces = namespaces;
        this.accounts = accounts;
        this.sharing = sharing;
        this.audit = audit;
    }

    /**
     * @param handle the account to share with, as a person types it. The id is what is stored
     * @param role   {@code viewer} or {@code editor}
     * @return the grantee and the role that now applies, or empty when there is no such skill for
     *         this caller — §4.1's one 404
     * @throws AccountRequestException for a role outside the two, a handle nobody has, or sharing
     *         with yourself
     */
    @Transactional
    public Optional<Granted> share(String namespaceSlug, String name, String handle, String role,
            AuthenticatedSubject subject) {
        if (!GrantRole.isRole(role)) {
            // A field error rather than a bare 400: the caller sent a value and there are exactly two
            // it could have meant, so saying which field was wrong is what lets them fix it.
            throw new AccountRequestException("role", "must_be_viewer_or_editor");
        }
        String granteeId = accounts.userIdOf(handle)
                // Absence is said plainly, and that is a choice rather than an oversight: it tells
                // the caller whether a username exists. Registration already answers that question
                // (`username/already_taken`), deliberately, so a second oracle here adds nothing —
                // and being vague would send somebody to check a spelling that is correct.
                .orElseThrow(() -> new AccountRequestException("handle", "no_such_user"));
        if (granteeId.equals(subject.userId())) {
            // Not a no-op to be absorbed: a grant row pointing at the owner would make "who owns
            // this" answerable two ways, and the owner already has both roles by owning it.
            throw new AccountRequestException("handle", "already_yours");
        }

        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        Caller caller = new Caller(subject.userId(), namespaces
                .personalNamespaceOf(subject.userId()).id());
        Optional<String> skillId = sharing.grant(namespace.get().id(), name, granteeId, role, caller,
                com.skillmasterai.common.Timestamps.now());
        skillId.ifPresent(id -> audit.record(new AuditEvent(subject.userId(), "share", "skill", id,
                Map.of("grantee", handle, "role", role))));
        return skillId.map(id -> new Granted(id, handle, role));
    }

    /** @param skillId the skill's identity, so the caller can audit and link without re-resolving */
    public record Granted(String skillId, String handle, String role) {
    }
}
