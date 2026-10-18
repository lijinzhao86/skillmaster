package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.SkillGrantRepository;
import com.skillmasterai.modules.skill.internal.SkillRepository;
import java.util.List;
import java.util.Optional;

/**
 * M12's sharing seam: who a skill is shared with, and changing that (ADR 0034).
 *
 * <p>Every method resolves the skill through {@link SkillRepository#permitted} first, so "may this
 * caller do this" is answered by the same statement that finds the skill — the arrangement §3.4
 * asks for, rather than a check that runs beside it and can be forgotten. What is checked here is
 * the {@code admin} rung: since ADR 0047 sharing is not the owner's alone, but it is the ladder's
 * top grantable rung. A lower rung that could re-share would let one grant spread without the
 * owner seeing any link in the chain.
 *
 * <p>The grantee is named by handle and stored by id — the address of a person at the moment it is
 * typed, and the identity afterwards, the same shape {@code VersionPin} has. Resolving the handle is
 * M1's, and it happens in the use case: this module may not read {@code app_user}.
 */
public final class SkillSharingService {

    private final SkillRepository skills;
    private final SkillGrantRepository grants;

    public SkillSharingService(SkillRepository skills, SkillGrantRepository grants) {
        this.skills = skills;
        this.grants = grants;
    }

    /**
     * Shares the skill with an account, or changes the role it was shared with.
     *
     * <p>One operation rather than grant-and-regrant, because the two are the same write: the row is
     * keyed by the pair, so assigning to it is how a role changes, and it makes the request
     * idempotent — which matters because the caller cannot ask whether a grant exists without
     * racing.
     *
     * @return empty when there is no such skill for this caller, or when it is not theirs to share
     * @throws NotPermittedException when they may see it and may not administer it
     */
    public Optional<String> grant(String namespaceId, String name, String granteeId, String role,
            Caller caller, String at) {
        Optional<SkillRepository.Permitted> resolved = permittedForAdministration(namespaceId, name,
                caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        String skillId = resolved.get().skill().id();
        grants.upsert(skillId, granteeId, role, caller.userId(), at);
        return Optional.of(skillId);
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
        Optional<SkillRepository.Permitted> resolved = permittedForAdministration(namespaceId, name,
                caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        String skillId = resolved.get().skill().id();
        return Optional.of(new Revocation(skillId, grants.delete(skillId, granteeId)));
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
        Optional<SkillRepository.Permitted> resolved = permittedForAdministration(namespaceId, name,
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
     * @return the skill's id, or empty when the caller cannot see it
     * @throws NotPermittedException when they can see it and hold less than {@code admin}
     */
    public Optional<String> setVisibility(String namespaceId, String name, String visibility,
            String publicRung, Caller caller, String at) {
        Optional<SkillRepository.Permitted> resolved = permittedForAdministration(namespaceId, name,
                caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        String skillId = resolved.get().skill().id();
        skills.setVisibility(skillId, visibility, publicRung, at);
        return Optional.of(skillId);
    }

    private Optional<SkillRepository.Permitted> permittedForAdministration(String namespaceId,
            String name, Caller caller) {
        Optional<SkillRepository.Permitted> resolved = skills.permitted(namespaceId, name, caller);
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
}
