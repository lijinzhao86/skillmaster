package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.notification.NotificationDraft;
import com.skillmasterai.modules.notification.NotificationKinds;
import com.skillmasterai.modules.notification.NotificationOutcome;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.SkillInvitationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sharing one skill with one account — now an invitation, not a grant (v45, ADR 0138).
 *
 * <p>The composition §2.5 names: M4 resolves the namespace the address names, M1 turns the handle
 * the caller typed into an account, M12 writes the invitation, M14 tells the invitee, and M10
 * records it. Nothing is granted until the invitee accepts.
 */
@Component
public class ShareSkillUseCase {

    private final NamespaceService namespaces;
    private final AccountDirectory accounts;
    private final SkillInvitationService invitations;
    private final NotificationService notifications;
    private final AuditLog audit;

    public ShareSkillUseCase(NamespaceService namespaces, AccountDirectory accounts,
            SkillInvitationService invitations, NotificationService notifications, AuditLog audit) {
        this.namespaces = namespaces;
        this.accounts = accounts;
        this.invitations = invitations;
        this.notifications = notifications;
        this.audit = audit;
    }

    /**
     * @param role one of the five rungs
     * @return the invitation, or empty when there is no such skill for this caller — §4.1's one 404
     * @throws AccountRequestException for a value outside the five, a handle nobody has, or sharing
     *         with yourself
     */
    @Transactional
    public Optional<Invited> share(String namespaceSlug, String name, String handle, String role,
            AuthenticatedSubject subject) {
        if (!GrantRole.isRole(role)) {
            throw new AccountRequestException("role", "not_a_rung");
        }
        String inviteeId = accounts.userIdOf(handle)
                .orElseThrow(() -> new AccountRequestException("handle", "no_such_user"));
        if (inviteeId.equals(subject.userId())) {
            throw new AccountRequestException("handle", "already_yours");
        }

        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        String at = Timestamps.now();
        Caller caller = new Caller(subject.userId(), namespaces
                .personalNamespaceOf(subject.userId()).id());
        Optional<SkillInvitationService.InviteOutcome> outcome = invitations.invite(
                namespace.get().id(), name, inviteeId, namespace.get().ownerUserId(), role,
                caller, at);
        if (outcome.isEmpty()) {
            return Optional.empty();
        }

        SkillInvitationService.InviteOutcome invited = outcome.get();
        String subjectKey = NotificationSubjects.invitation(invited.skillId(), inviteeId);
        if (invited.expiredPrevious()) {
            notifications.resolve(subjectKey, NotificationKinds.SHARE_INVITATION,
                    NotificationOutcome.EXPIRED, at);
        }
        if (!invited.alreadyGranted()) {
            String actorHandle = accounts.handleOf(subject.userId()).orElse(null);
            notifications.emit(new NotificationDraft(
                    NotificationKinds.SHARE_INVITATION,
                    subjectKey,
                    subject.userId(),
                    actorHandle,
                    invited.skillId(),
                    invited.role(),
                    null,
                    null,
                    invited.expiresAt(),
                    List.of(inviteeId)), at);
        }

        audit.record(new AuditEvent(subject.userId(), "share", "skill", invited.skillId(),
                Map.of("grantee", handle,
                        "role", invited.role(),
                        "already_granted", invited.alreadyGranted())));

        return Optional.of(new Invited(invited.skillId(), handle, invited.role(),
                invited.expiresAt(), invited.alreadyGranted()));
    }

    /**
     * @param expiresAt null when the invitee already held this rung or higher; nothing was written
     */
    public record Invited(String skillId, String handle, String role, String expiresAt,
            boolean alreadyGranted) {
    }
}
