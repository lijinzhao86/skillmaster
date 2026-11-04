package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.notification.NotificationDraft;
import com.skillmasterai.modules.notification.NotificationKinds;
import com.skillmasterai.modules.notification.NotificationOutcome;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.skill.SkillAccessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approves or denies one pending request (ADR 0052, v45).
 *
 * <p><strong>The approved rung is the approver's.</strong> Approval writes the grant through the
 * same path a direct share takes, with the rung this call carries — which may differ from the one
 * requested. Denial deletes the request and touches no grant. Either way the request row is gone.
 *
 * <p>v45 closes the loop the old version left open: the approvers' notification is resolved with
 * the result, and the requester gets a result notification of their own. The audit row still keeps
 * both rungs.
 */
@Component
public class DecideSkillAccessRequestUseCase {

    private final NamespaceService namespaces;
    private final AccountDirectory accounts;
    private final SkillAccessService access;
    private final NotificationService notifications;
    private final AuditLog audit;

    public DecideSkillAccessRequestUseCase(NamespaceService namespaces, AccountDirectory accounts,
            SkillAccessService access, NotificationService notifications, AuditLog audit) {
        this.namespaces = namespaces;
        this.accounts = accounts;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
    }

    /**
     * The address-shaped entry, used by the console's collaborators pane.
     *
     * @throws AccountRequestException when the handle names nobody
     */
    @Transactional
    public Optional<Decision> decide(String namespaceSlug, String name, String handle,
            String approvedRung, boolean approve, AuthenticatedSubject subject) {
        String requesterId = accounts.userIdOf(handle)
                .orElseThrow(() -> new AccountRequestException("handle", "no_such_user"));
        String at = Timestamps.now();
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> access.decide(namespace.id(), name, requesterId,
                        approvedRung, approve, Callers.of(namespaces, subject), at))
                .map(outcome -> recordDecision(outcome, approvedRung, approve, subject, at));
    }

    /**
     * The identity-shaped entry, reached from a notification by the CLI.
     *
     * <p>Same decision, same notification side effects, same audit row; only the lookup of the
     * skill differs. {@code notifying} is true for both entries, so the two planes cannot drift.
     */
    @Transactional
    public Optional<Decision> decideBySkillId(String skillId, String requesterId,
            String approvedRung, boolean approve, AuthenticatedSubject subject) {
        String at = Timestamps.now();
        return access.decideById(skillId, requesterId, approvedRung, approve,
                        Callers.of(namespaces, subject), at)
                .map(outcome -> recordDecision(outcome, approvedRung, approve, subject, at));
    }

    private Decision recordDecision(SkillAccessService.DecisionOutcome outcome,
            String approvedRung, boolean approve, AuthenticatedSubject subject, String at) {
        String requesterId = outcome.requesterId();
        String subjectKey = NotificationSubjects.accessRequest(outcome.skillId(), requesterId);
        NotificationOutcome result = approve ? NotificationOutcome.APPROVED
                : NotificationOutcome.REJECTED;

        notifications.resolve(subjectKey, NotificationKinds.ACCESS_REQUEST, result, at);
        String actorHandle = accounts.handleOf(subject.userId()).orElse(null);
        notifications.emit(new NotificationDraft(
                NotificationKinds.ACCESS_REQUEST_RESULT,
                subjectKey,
                subject.userId(),
                actorHandle,
                outcome.skillId(),
                approve ? approvedRung : outcome.wantedRole(),
                null,
                null,
                null,
                List.of(requesterId)), at);
        // The result is a message, not a request: it is closed the moment it is written. Resolving
        // it here is what gives the row its displayed state (`approved` / `rejected`) instead of
        // leaving it reading as another pending request.
        notifications.resolve(subjectKey, NotificationKinds.ACCESS_REQUEST_RESULT, result, at);

        String handle = accounts.handleOf(requesterId).orElse(null);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("account", handle);
        detail.put("requested", outcome.wantedRole());
        detail.put("granted", approve ? approvedRung : "none");
        audit.record(new AuditEvent(subject.userId(), approve ? "approve" : "deny",
                "skill", outcome.skillId(), detail));

        return new Decision(outcome.skillId(), handle, outcome.wantedRole(),
                approve ? approvedRung : null, approve);
    }

    /** @param granted the rung granted, or null when the request was denied */
    public record Decision(String skillId, String handle, String wantedRole, String granted,
            boolean approved) {
    }
}
