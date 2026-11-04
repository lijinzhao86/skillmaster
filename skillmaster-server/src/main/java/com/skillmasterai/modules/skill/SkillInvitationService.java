package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.Permitted;
import com.skillmasterai.modules.skill.internal.SkillGrantRepository;
import com.skillmasterai.modules.skill.internal.SkillInvitationRepository;
import com.skillmasterai.modules.skill.internal.SkillRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * M12's invitation seam: offer a share, answer one, take one back (ADR 0138).
 *
 * <p><strong>An invitation is not a grant.</strong> {@link #invite} writes
 * {@code skill_invitation} and touches no access predicate, which is why the invitee still cannot
 * read a private skill before {@link #accept}. Accepting writes the grant through the same
 * {@link SkillGrantRepository#upsert} a direct share used to take.
 *
 * <p>The inviting side is gated by {@code admin}, exactly as sharing was: the ladder's top
 * grantable rung is what may pass a skill on.
 */
public final class SkillInvitationService {

    /** The one fixed lifetime this version offers (ADR 0138). */
    public static final int EXPIRES_AFTER_DAYS = 30;

    private final SkillRepository skills;
    private final SkillGrantRepository grants;
    private final SkillInvitationRepository invitations;

    public SkillInvitationService(SkillRepository skills, SkillGrantRepository grants,
            SkillInvitationRepository invitations) {
        this.skills = skills;
        this.grants = grants;
        this.invitations = invitations;
    }

    /**
     * Offers the rung, or says the invitee already has it.
     *
     * <p>An existing grant at or above the offered rung produces no invitation: there is nothing
     * to accept, and writing one would offer a person a downgrade. An expired invitation is
     * removed first, and the answer says so — the caller closes that notification as expired
     * before emitting the new one.
     *
     * @return empty when there is no such skill for this caller, or it is not theirs to share
     * @throws NotPermittedException when they may see it and may not administer it
     */
    public Optional<InviteOutcome> invite(String namespaceId, String name, String inviteeId,
            String ownerId, String role, Caller caller, String at) {
        Optional<Permitted> resolved = permittedForAdministration(namespaceId, name, caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        // After the authorization answer: a stranger or a lower rung must get the same 404/403 they
        // get for any other address, not learn who the owner is from a different error.
        if (inviteeId.equals(ownerId)) {
            throw new SkillRequestException("handle", "already_yours");
        }
        String skillId = resolved.get().skill().id();

        // Close an expired row before the "already granted" answer: leaving it behind would keep its
        // notification open even though the person no longer needs the invitation.
        boolean expiredPrevious = invitations.find(skillId, inviteeId)
                .map(existing -> existing.expiredAt(at))
                .orElse(false);
        if (expiredPrevious) {
            invitations.delete(skillId, inviteeId);
        }

        Optional<String> held = grants.roleOf(skillId, inviteeId);
        if (held.isPresent() && GrantRole.atLeast(held.get(), role)) {
            return Optional.of(new InviteOutcome(skillId, held.get(), true, null, expiredPrevious));
        }

        String expiresAt = Instant.parse(at).plus(EXPIRES_AFTER_DAYS, ChronoUnit.DAYS).toString();
        invitations.upsert(skillId, inviteeId, role, caller.userId(), at, expiresAt);
        return Optional.of(new InviteOutcome(skillId, role, false, expiresAt, expiredPrevious));
    }

    /**
     * Accepts the invitation and writes the grant.
     *
     * <p>An expired invitation is deleted and reported as {@code EXPIRED}; the caller resolves its
     * notification and answers 409. Keeping that decision as a value rather than an exception is
     * what lets both writes commit in one transaction.
     *
     * @return empty when there is no invitation for this person on this skill
     */
    public Optional<AcceptOutcome> accept(String skillId, String inviteeId, String at) {
        // Delete first: the row is the claim. A concurrent decline / revoke either wins this delete
        // and this call returns empty, or loses it and finds nothing to do. Reading first and
        // deleting later let a successful revoke be undone by an accept that had already read.
        Optional<SkillInvitation> claimed = invitations.delete(skillId, inviteeId);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        SkillInvitation invitation = claimed.get();
        if (invitation.expiredAt(at)) {
            return Optional.of(new AcceptOutcome(AcceptOutcome.State.EXPIRED, skillId,
                    invitation.role(), invitation.invitedBy()));
        }
        if (!skills.isLive(skillId)) {
            // The skill was deleted after the invitation was sent. Accepting would write a grant for
            // a skill the invitee cannot see, which a later restore would silently reactivate.
            return Optional.of(new AcceptOutcome(AcceptOutcome.State.GONE, skillId,
                    invitation.role(), invitation.invitedBy()));
        }
        // The invitation may be older than a grant the person has since received. Accepting must not
        // move a higher rung down: the effective rung is the higher of the two.
        Optional<String> held = grants.roleOf(skillId, inviteeId);
        String effective = held.filter(role -> GrantRole.atLeast(role, invitation.role()))
                .orElse(invitation.role());
        if (held.isEmpty() || !effective.equals(held.get())) {
            grants.upsert(skillId, inviteeId, effective, invitation.invitedBy(), at);
        }
        return Optional.of(new AcceptOutcome(AcceptOutcome.State.ACCEPTED, skillId,
                effective, invitation.invitedBy()));
    }

    /** Declines: nothing is granted, the invitation row goes away. */
    public Optional<Declined> decline(String skillId, String inviteeId, String at) {
        return invitations.delete(skillId, inviteeId)
                .map(row -> new Declined(skillId, row.role(), row.invitedBy(), row.expiredAt(at)));
    }

    /**
     * Withdraws a pending invitation — the {@code admin} rung's view.
     *
     * @return the skill and the offered rung, or empty when there is no such skill for this caller
     * @throws NotPermittedException when they may see it and may not administer it
     */
    public Optional<Revoked> revoke(String namespaceId, String name, String inviteeId,
            Caller caller) {
        Optional<Permitted> resolved = permittedForAdministration(namespaceId, name, caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        String skillId = resolved.get().skill().id();
        return Optional.of(new Revoked(skillId,
                invitations.delete(skillId, inviteeId).map(SkillInvitation::role)));
    }

    /**
     * Every invitation on this skill — the {@code admin} rung's view.
     *
     * <p>Expired rows stay in the answer so the inviter can see who never answered; the caller
     * decides how to label them from {@code expires_at} (ADR 0138 §决定 4).
     *
     * @throws NotPermittedException when the caller can see the skill and holds less than
     *         {@code admin}
     */
    public Optional<List<SkillInvitation>> invitationsFor(String namespaceId, String name,
            Caller caller) {
        Optional<Permitted> resolved = permittedForAdministration(namespaceId, name, caller);
        return resolved.map(permitted -> invitations.allOf(permitted.skill().id()));
    }

    private Optional<Permitted> permittedForAdministration(String namespaceId, String name,
            Caller caller) {
        Optional<Permitted> resolved = skills.permitted(namespaceId, name, caller);
        if (resolved.isPresent() && !resolved.get().atLeast(GrantRole.ADMIN)) {
            throw new NotPermittedException("sharing '" + name + "' needs the `admin` rung; "
                    + "a lower rung does not let you pass it on");
        }
        return resolved;
    }

    /**
     * @param alreadyGranted true when the invitee already held this rung or higher; no row was
     *                       written and {@code expiresAt} is null
     * @param expiredPrevious true when an expired invitation was removed before this one was
     *                        written, so the caller can close its notification
     */
    public record InviteOutcome(String skillId, String role, boolean alreadyGranted,
            String expiresAt, boolean expiredPrevious) {
    }

    /**
     * @param state ACCEPTED, EXPIRED, or GONE (the skill was deleted); an absent invitation is the
     *              empty return, not a state
     */
    public record AcceptOutcome(State state, String skillId, String role, String invitedBy) {

        public enum State { ACCEPTED, EXPIRED, GONE }
    }

    /** @param expired true when the row was already past {@code expires_at}; the archive says so */
    public record Declined(String skillId, String role, String invitedBy, boolean expired) {
    }

    /** @param removedRole empty when there was no invitation to withdraw */
    public record Revoked(String skillId, Optional<String> removedRole) {
    }
}
