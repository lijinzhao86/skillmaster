package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillAccessService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approves or denies one pending request (ADR 0052).
 *
 * <p><strong>The approved rung is the approver's.</strong> Approval writes the grant through the
 * same path a direct share takes, with the rung this call carries — which may differ from the one
 * requested. Denial deletes the request and touches no grant. Either way the request row is gone,
 * so answering it twice is §4.1's one 404 and not a second decision.
 *
 * <p>The audit row keeps both rungs: the one asked for and the one granted. That is where the gap
 * ADR 0052 §后果 accepted — no notification that the request was answered with a different rung —
 * is still recoverable by somebody reading the trail.
 */
@Component
public class DecideSkillAccessRequestUseCase {

    private final NamespaceService namespaces;
    private final AccountDirectory accounts;
    private final SkillAccessService access;
    private final AuditLog audit;

    public DecideSkillAccessRequestUseCase(NamespaceService namespaces, AccountDirectory accounts,
            SkillAccessService access, AuditLog audit) {
        this.namespaces = namespaces;
        this.accounts = accounts;
        this.access = access;
        this.audit = audit;
    }

    /**
     * @param handle      the requester, as a person types it
     * @param approvedRung the rung to grant when approving; ignored when denying
     * @param approve     true grants and closes the request; false only closes it
     * @return the decided request, or empty when there is no such skill or no such pending request
     *         — §4.1's one 404
     * @throws AccountRequestException when the handle names nobody
     */
    @Transactional
    public Optional<Decision> decide(String namespaceSlug, String name, String handle,
            String approvedRung, boolean approve, AuthenticatedSubject subject) {
        String requesterId = accounts.userIdOf(handle)
                .orElseThrow(() -> new AccountRequestException("handle", "no_such_user"));

        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> access.decide(namespace.id(), name, requesterId, approvedRung,
                        approve, Callers.of(namespaces, subject), Timestamps.now()))
                .map(outcome -> {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("account", handle);
                    detail.put("requested", outcome.wantedRole());
                    detail.put("granted", approve ? approvedRung : "none");
                    audit.record(new AuditEvent(subject.userId(), approve ? "approve" : "deny",
                            "skill", outcome.skillId(), detail));
                    return new Decision(outcome.skillId(), handle, outcome.wantedRole(),
                            approve ? approvedRung : null, approve);
                });
    }

    /** @param granted the rung granted, or null when the request was denied */
    public record Decision(String skillId, String handle, String wantedRole, String granted,
            boolean approved) {
    }
}
