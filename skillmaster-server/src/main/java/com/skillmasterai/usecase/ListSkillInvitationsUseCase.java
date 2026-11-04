package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillInvitation;
import com.skillmasterai.modules.skill.SkillInvitationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every invitation on a skill, pending and expired — the inviter's view.
 *
 * <p>It is not the invitee's queue: acceptance lives in the notification, because that is where
 * the invitee was told. This side can see, withdraw, and re-share; expired rows stay visible so the
 * owner can tell "never answered" from "accepted".
 */
@Component
public class ListSkillInvitationsUseCase {

    private final NamespaceService namespaces;
    private final AccountDirectory accounts;
    private final SkillInvitationService invitations;

    public ListSkillInvitationsUseCase(NamespaceService namespaces, AccountDirectory accounts,
            SkillInvitationService invitations) {
        this.namespaces = namespaces;
        this.accounts = accounts;
        this.invitations = invitations;
    }

    @Transactional(readOnly = true)
    public Optional<List<View>> list(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        Caller caller = Callers.of(namespaces, subject);
        String now = Timestamps.now();
        Optional<List<SkillInvitation>> rows = invitations.invitationsFor(namespace.get().id(),
                name, caller);
        return rows.map(found -> {
            Map<String, String> handles = accounts.handlesOf(
                    found.stream().map(SkillInvitation::inviteeId).toList());
            return found.stream()
                    .map(row -> new View(handles.get(row.inviteeId()), row.role(),
                            row.createdAt(), row.expiresAt(),
                            row.expiredAt(now) ? "expired" : "pending"))
                    .toList();
        });
    }

    /** @param handle null only if the account disappeared between the two reads */
    public record View(String handle, String role, String createdAt, String expiresAt,
            String state) {
    }
}
