package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.SkillAccessRequestRepository;
import com.skillmasterai.modules.skill.internal.SkillGrantRepository;
import com.skillmasterai.modules.skill.internal.SkillRepository;
import java.util.List;
import java.util.Optional;

/**
 * M12's seam for asking for a rung and answering the ask (ADR 0052).
 *
 * <p><strong>A request is not a grant, and this class keeps the two from touching.</strong> Asking
 * writes one row in {@code skill_access_request} and changes nothing about what anybody can do;
 * approving writes {@code skill_grant} through {@link SkillGrantRepository}'s upsert — the same path
 * a direct share takes — and deletes the request. No access predicate reads the request table, so
 * neither the ladder nor the per-rung filtering has to know the word "request".
 *
 * <p><strong>Only people who can see the skill can ask.</strong> Every method starts from
 * {@link SkillRepository#permitted}, so a private skill's stranger gets the same empty result as an
 * address that does not exist. That is the shape ADR 0052 §决定 2 asks for; the alternative — a 404
 * page that says "you could ask" — would tear the hole that "private" is defined by not having.
 */
public final class SkillAccessService {

    private final SkillRepository skills;
    private final SkillGrantRepository grants;
    private final SkillAccessRequestRepository requests;

    public SkillAccessService(SkillRepository skills, SkillGrantRepository grants,
            SkillAccessRequestRepository requests) {
        this.skills = skills;
        this.grants = grants;
        this.requests = requests;
    }

    /**
     * The caller's own pending request on a skill they have already resolved, if there is one.
     *
     * <p><strong>For the page, not for the queue.</strong> The information page draws "waiting for a
     * decision" instead of the form (design's 「等待处理」), and that state has to survive a reload —
     * which is what makes it a read rather than the page remembering its own click. It takes an
     * {@link Access} because reaching the skill has already been decided by {@code accessFor}; this
     * answers only "and did this person ask for something".
     *
     * @return the rung asked for, or empty when nothing is pending
     */
    public Optional<AccessRequest> mine(Access access, Caller caller) {
        return requests.find(access.skillId(), caller.userId());
    }

    /**
     * Records "I want this rung", or moves the request already pending.
     *
     * <p>The rung must be one the ladder has and one the caller does not already hold: the owner
     * and anybody at {@code admin} have nothing above them, and a request for a rung at or below
     * one's own is a request that could only be answered with what is already true. Both are the
     * caller's to fix, so both are 400s rather than 403s.
     *
     * @return the skill's id, or empty when the caller cannot see it — §4.1's one 404
     * @throws SkillRequestException for a value outside the five, or one not above the caller's own
     */
    public Optional<String> request(String namespaceId, String name, String wantedRole,
            Caller caller, String at) {
        if (!GrantRole.isRole(wantedRole)) {
            throw new SkillRequestException("rung", "not_a_rung");
        }
        Optional<SkillRepository.Permitted> resolved = skills.permitted(namespaceId, name, caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        SkillRepository.Permitted permitted = resolved.get();
        if (permitted.owns()) {
            throw new SkillRequestException("rung", "owner_has_no_higher_rung");
        }
        if (!GrantRole.isAbove(wantedRole, permitted.rung())) {
            throw new SkillRequestException("rung", "not_above_current_rung");
        }
        String skillId = permitted.skill().id();
        // Validation is above, not inside the upsert: a refused request must not replace the one
        // already pending (TD §接口). The two facts are deliberately in this order.
        requests.upsert(skillId, caller.userId(), wantedRole, at);
        return Optional.of(skillId);
    }

    /**
     * Withdraws the caller's own request.
     *
     * <p>Idempotent, like withdrawing a share: the state the caller asks for — "I am not waiting" —
     * holds whether or not a row was there. The answer says which it was for the audit trail, not
     * for the wire.
     *
     * @return the skill and whether a request was actually removed, or empty when the caller cannot
     *         see the skill
     */
    public Optional<Withdrawal> withdraw(String namespaceId, String name, Caller caller) {
        Optional<SkillRepository.Permitted> resolved = skills.permitted(namespaceId, name, caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        String skillId = resolved.get().skill().id();
        return Optional.of(new Withdrawal(skillId,
                requests.delete(skillId, caller.userId()).isPresent()));
    }

    /**
     * Everyone waiting on this skill, oldest first — the {@code admin} rung's view.
     *
     * @return the requests, or empty when the caller cannot see the skill
     * @throws NotPermittedException when they can see it and hold less than {@code admin}
     */
    public Optional<List<AccessRequest>> pendingFor(String namespaceId, String name, Caller caller) {
        Optional<SkillRepository.Permitted> resolved = skills.permitted(namespaceId, name, caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        requireAdmin(resolved.get(), name);
        return Optional.of(requests.pendingFor(resolved.get().skill().id()));
    }

    /**
     * Answers one request: approve (grant the chosen rung) or deny (grant nothing). Either way the
     * request row goes away.
     *
     * <p>The approved rung is the approver's, not the requester's, which is the whole point of
     * keeping {@code wanted_role} on the request until this moment (ADR 0052 §决定 3).
     *
     * @return the skill and the rung that was asked for, or empty when the caller cannot see the
     *         skill <em>or</em> the request is already gone — both §4.1's one 404
     * @throws NotPermittedException when they can see the skill and hold less than {@code admin}
     * @throws SkillRequestException for an approved rung outside the five
     */
    public Optional<DecisionOutcome> decide(String namespaceId, String name, String requesterId,
            String approvedRole, boolean approve, Caller caller, String at) {
        if (approve && !GrantRole.isRole(approvedRole)) {
            throw new SkillRequestException("rung", "not_a_rung");
        }
        Optional<SkillRepository.Permitted> resolved = skills.permitted(namespaceId, name, caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        SkillRepository.Permitted permitted = resolved.get();
        requireAdmin(permitted, name);
        String skillId = permitted.skill().id();

        Optional<AccessRequest> found = requests.find(skillId, requesterId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (approve) {
            // The same upsert a direct share uses, so the ladder, the audit and the per-rung
            // filtering never learn the word "request" (ADR 0052 §决定 1).
            grants.upsert(skillId, requesterId, approvedRole, caller.userId(), at);
        }
        requests.delete(skillId, requesterId);
        return Optional.of(new DecisionOutcome(skillId, found.get().wantedRole()));
    }

    private static void requireAdmin(SkillRepository.Permitted permitted, String name) {
        if (!permitted.atLeast(GrantRole.ADMIN)) {
            throw new NotPermittedException("answering requests for '" + name
                    + "' needs the `admin` rung");
        }
    }

    /** @param removed false when nothing was pending to withdraw */
    public record Withdrawal(String skillId, boolean removed) {
    }

    /** @param wantedRole what the requester asked for; the approver may have granted another rung */
    public record DecisionOutcome(String skillId, String wantedRole) {
    }
}
