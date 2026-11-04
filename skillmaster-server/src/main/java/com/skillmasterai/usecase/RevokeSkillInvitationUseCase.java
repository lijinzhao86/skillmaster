package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.notification.NotificationKinds;
import com.skillmasterai.modules.notification.NotificationOutcome;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillInvitationService;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdraws a pending invitation — the inviter's side (v45).
 *
 * <p>Idempotent like withdrawing a grant: "this person must no longer be invited" holds whether or
 * not a row was there. The invitee's notification is closed as revoked either way, so a row that
 * expired between the read and this call does not leave an open invitation in their feed.
 */
@Component
public class RevokeSkillInvitationUseCase {

    private final NamespaceService namespaces;
    private final AccountDirectory accounts;
    private final SkillInvitationService invitations;
    private final NotificationService notifications;
    private final AuditLog audit;

    public RevokeSkillInvitationUseCase(NamespaceService namespaces, AccountDirectory accounts,
            SkillInvitationService invitations, NotificationService notifications, AuditLog audit) {
        this.namespaces = namespaces;
        this.accounts = accounts;
        this.invitations = invitations;
        this.notifications = notifications;
        this.audit = audit;
    }

    /**
     * @return the skill and whether an invitation was actually removed, or empty when there is no
     *         such skill for this caller
     * @throws AccountRequestException when the handle names nobody
     */
    @Transactional
    public Optional<Revoked> revoke(String namespaceSlug, String name, String handle,
            AuthenticatedSubject subject) {
        String inviteeId = accounts.userIdOf(handle)
                .orElseThrow(() -> new AccountRequestException("handle", "no_such_user"));
        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        Caller caller = Callers.of(namespaces, subject);
        return invitations.revoke(namespace.get().id(), name, inviteeId, caller)
                .map(revoked -> {
                    String at = Timestamps.now();
                    notifications.resolve(
                            NotificationSubjects.invitation(revoked.skillId(), inviteeId),
                            NotificationKinds.SHARE_INVITATION,
                            NotificationOutcome.REVOKED, at);
                    audit.record(new AuditEvent(subject.userId(), "revoke_invitation", "skill",
                            revoked.skillId(), Map.of("invitee", handle,
                                    "role", revoked.removedRole().orElse("none"))));
                    return new Revoked(revoked.skillId(), handle,
                            revoked.removedRole().isPresent());
                });
    }

    public record Revoked(String skillId, String handle, boolean removed) {
    }
}
