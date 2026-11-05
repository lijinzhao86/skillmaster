package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.SkillGrantRepository;
import com.skillmasterai.modules.skill.internal.Permitted;
import com.skillmasterai.modules.skill.internal.SkillRepository;
import java.util.List;
import java.util.Optional;

/**
 * M12's sharing seam: who a skill is shared with, and the revoke / list / visibility half of it.
 *
 * <p><strong>New shares are invitations since v45 (ADR 0138).</strong> {@link SkillInvitationService}
 * writes {@code skill_invitation}; this class still owns the operations that act on an existing
 * grant — withdrawing it, listing it, and changing visibility. The two are the same RPC family but
 * different objects, and keeping a direct {@code grant} method here would leave a way to bypass the
 * invitation the product now requires.
 *
 * <p>Every method resolves the skill through {@link SkillRepository#permitted} first, so "may this
 * caller do this" is answered by the same statement that finds the skill — the arrangement §3.4
 * asks for, rather than a check that runs beside it and can be forgotten. What is checked here is
 * the {@code admin} rung: since ADR 0047 sharing is not the owner's alone, but it is the ladder's
 * top grantable rung. A lower rung that could re-share would let one grant spread without the
 * owner seeing any link in the chain.
 */
public final class SkillSharingService {

    private final SkillRepository skills;
    private final SkillGrantRepository grants;

    public SkillSharingService(SkillRepository skills, SkillGrantRepository grants) {
        this.skills = skills;
        this.grants = grants;
    }

    /**
     * Withdraws the grant.
     *
     * @return the skill's id and the role that was removed, or empty when there was no such skill for
     *         this caller. <strong>The role may be empty while the skill id is present</strong>:
     *         revoking a grant that is not there is a success, because the state the caller asked for
     *         — nobody has this — is the state there is
     */
    public Optional<Revocation> revoke(String namespaceId, String name, String granteeId,
            Caller caller) {
        Optional<Permitted> resolved = permittedForAdministration(namespaceId, name,
                caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        String skillId = resolved.get().skill().id();
        return Optional.of(new Revocation(skillId, grants.delete(skillId, granteeId)));
    }

    /**
     * Moves an existing grant down (or leaves it where it is).
     *
     * <p><strong>Lowering is not a share.</strong> It takes nothing new from the skill and needs no
     * consent, so it is immediate; raising goes through [ADR 0138]'s invitation because it is new
     * access. A role above the one held is refused here rather than silently upgraded — that would
     * be the direct grant v45 removed.
     *
     * @return the change, or empty when no grant exists for that person (the route answers 404)
     * @throws SkillRequestException for a value outside the five, or a rung above the one held
     */
    public Optional<RoleChange> changeRole(String namespaceId, String name, String granteeId,
            String role, Caller caller, String at) {
        if (!GrantRole.isRole(role)) {
            throw new SkillRequestException("role", "not_a_rung");
        }
        Optional<Permitted> resolved = permittedForAdministration(namespaceId, name, caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        String skillId = resolved.get().skill().id();
        Optional<String> held = grants.roleOf(skillId, granteeId);
        if (held.isEmpty()) {
            return Optional.empty();
        }
        if (GrantRole.isAbove(role, held.get())) {
            throw new SkillRequestException("role", "raise_needs_invitation");
        }
        boolean changed = !role.equals(held.get());
        if (changed) {
            grants.upsert(skillId, granteeId, role, caller.userId(), at);
        }
        return Optional.of(new RoleChange(skillId, held.get(), role, changed));
    }

    /**
     * Everyone this skill has been shared with.
     *
     * <p>The owner's view, and only the owner's: what is shared with whom is not something a grantee
     * needs, and telling them would be telling them about other people.
     *
     * @return the grants, or empty when there is no such skill for this caller or it is not theirs
     * @throws NotPermittedException when they may read it and may not administer it
     */
    public Optional<List<SkillGrant>> grantsOf(String namespaceId, String name,
            Caller caller) {
        Optional<Permitted> resolved = permittedForAdministration(namespaceId, name,
                caller);
        return resolved.map(permitted -> grants.grantsOf(permitted.skill().id()));
    }

    /**
     * Sets the skill's visibility — whether it is public, and which rung a stranger gets (ADR 0048).
     *
     * <p>Here rather than in a service of its own because it is the same question this class answers
     * for one person, asked of everybody: which rung does this account hold. The {@code admin} rung
     * is the gate, exactly as it is for a grant. The two values are validated one layer up, where a
     * malformed request is a 400 rather than a domain error.
     *
     * @param publicAllowed whether this skill's namespace may be public (ADR 0146). Checked after
     *                      the rung, so an invisible skill stays a 404 and a visible one below
     *                      {@code admin} stays a 403; only a caller who could otherwise make the
     *                      change learns the policy refusal
     * @return the skill's id, or empty when the caller cannot see it
     * @throws NotPermittedException when they can see it and hold less than {@code admin}
     * @throws PublicIsOfficialOnlyException when they may administer it but the namespace is not
     *                                      one the platform lets into the public catalogue
     */
    public Optional<String> setVisibility(String namespaceId, String name, String visibility,
            String publicRung, Caller caller, String at, boolean publicAllowed) {
        Optional<Permitted> resolved = permittedForAdministration(namespaceId, name,
                caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        if ("public".equals(visibility) && !publicAllowed) {
            throw new PublicIsOfficialOnlyException();
        }
        String skillId = resolved.get().skill().id();
        skills.setVisibility(skillId, visibility, publicRung, at);
        return Optional.of(skillId);
    }

    private Optional<Permitted> permittedForAdministration(String namespaceId,
            String name, Caller caller) {
        Optional<Permitted> resolved = skills.permitted(namespaceId, name, caller);
        if (resolved.isPresent() && !resolved.get().atLeast(GrantRole.ADMIN)) {
            throw new NotPermittedException("sharing '" + name + "' needs the `admin` rung; "
                    + "a lower rung does not let you pass it on");
        }
        return resolved;
    }

    /**
     * @param skillId the skill, so the caller can audit against its identity rather than its address
     * @param removedRole the role that was taken away, or empty when nothing was there to remove.
     *                    The audit row distinguishes the two — "revoked an editor grant" and
     *                    "revoked a grant that had already gone" are different histories
     */
    public record Revocation(String skillId, Optional<String> removedRole) {
    }

    /** @param changed false when the requested rung is the one already held */
    public record RoleChange(String skillId, String from, String to, boolean changed) {
    }
}
