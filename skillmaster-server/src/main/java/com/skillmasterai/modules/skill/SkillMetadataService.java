package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.SkillRepository;
import java.util.Optional;

/**
 * M12: a skill's address, identity, and the projections its lists read (ADR 0053).
 *
 * <p><strong>This class is the seam the content modules use.</strong> After the split, M7 owns the
 * versions and the bytes but not the skill row, so three questions that used to be answered by
 * reading {@code skill} are answered here instead:
 * <ul>
 *   <li>{@link #accessFor} — may this caller reach this address, and how far (an {@link Access});</li>
 *   <li>{@link #findOrCreate} — the address row a first submission creates;</li>
 *   <li>{@link #recordSubmission}, {@link #recordDiscard}, {@link #recordPublish} — the facts M7
 *       computes from its own tables, written onto {@code skill} so a list never has to join
 *       across the boundary.</li>
 * </ul>
 *
 * <p><strong>Nothing here opens a transaction.</strong> A submission, a publish and a discard each
 * span both modules, so the boundary belongs to the use case (§2.5 rule 2). Every method is written
 * to run inside the transaction its caller owns; running one outside would still work and would
 * split one change into two commits.
 *
 * <p><strong>The projections are the price of the split, and this class is where that price is
 * paid.</strong> Each of the three record methods is a maintenance point: forget one and the list
 * shows a stale fact with a perfectly well-formed response. The test plan's T14/T15 pin them.
 */
public final class SkillMetadataService {

    private final SkillRepository skills;

    public SkillMetadataService(SkillRepository skills) {
        this.skills = skills;
    }

    /**
     * The skill with this name in this namespace, together with what this caller may do to it.
     *
     * <p>One statement answers three questions — the row, whether the caller owns it, and the
     * effective rung — because a read that split them would make each write path ask twice, and the
     * second question is exactly the one a later edit forgets. The rung is the higher of the grant
     * and the public rung (ADR 0048), so a public skill nobody was granted still produces a row.
     *
     * @return empty when there is no such live skill, <em>or</em> when there is one this caller may
     *         not see — one answer for both, deliberately (§4.1)
     */
    public Optional<Access> accessFor(String namespaceId, String name, Caller caller) {
        return skills.permitted(namespaceId, name, caller).map(permitted -> {
            SkillRepository.SkillRow row = permitted.skill();
            return new Access(
                    row.id(),
                    row.namespaceId(),
                    row.name(),
                    row.title(),
                    row.description(),
                    row.frontmatter(),
                    row.visibility(),
                    row.publicRung(),
                    row.currentVersionId(),
                    permitted.owns(),
                    permitted.rung(),
                    caller.userId());
        });
    }

    /**
     * Creates the address row if the name is new, and returns its id either way.
     *
     * <p>Called by the submit use case before M7 records the version, so that the row's lock is
     * held for the whole submission — the number allocation in {@code skill_version} depends on it
     * (see {@code VersionRepository#insertIfAbsent}). A soft-deleted skill returns no row, which is
     * what lets the caller refuse instead of resurrecting it.
     *
     * @return the skill's id, or empty when the name belongs to a soft-deleted skill
     */
    public Optional<String> findOrCreate(String namespaceId, SkillMetadata metadata,
            String createdBy, String at) {
        return skills.findOrCreate(namespaceId, metadata, createdBy, at);
    }

    /**
     * Rewrites the draft-side projection after a version was submitted.
     *
     * <p>The {@link DraftProjection} is computed by M7 — it is the only module that may read
     * {@code skill_version}. This method writes it, and nothing else: a submission changes what is
     * waiting, never what is live.
     */
    public void recordSubmission(String skillId, DraftProjection drafts) {
        skills.recordDraftState(skillId, drafts);
    }

    /**
     * Rewrites the draft-side projection after a draft was discarded.
     *
     * <p>Kept apart from {@link #recordSubmission} although the statement is the same, because the
     * two events are different facts and a later edit to one must not silently move the other.
     */
    public void recordDiscard(String skillId, DraftProjection drafts) {
        skills.recordDraftState(skillId, drafts);
    }

    /**
     * Moves the pointer and writes every projection a list reads.
     *
     * <p>The content module marks the version published first, then hands over the facts. Both
     * writes are in the caller's transaction, so the pointer never names something that is not
     * published even for the inside of that transaction. The draft side is recomputed in the same
     * call because the version that just went live has left the draft set.
     *
     * @return whether the pointer actually moved. Zero means it already named this version — the
     *         loser of a race, or a second click on 上线 — and the caller must not audit it
     */
    public boolean recordPublish(String skillId, VersionProjection version, DraftProjection drafts,
            String at) {
        return skills.recordPublish(skillId, version, drafts, at);
    }

    /**
     * Soft-deletes the live skill this address names — <strong>the owner's only</strong>.
     *
     * <p><strong>Two answers, and they are different on purpose.</strong> A caller who cannot reach
     * the address gets §4.1's one 404. A caller who <em>can</em> see it and does not own it gets a
     * 403: deletion was deliberately kept off the ladder (ADR 0047 §决定 4), an {@code admin}
     * grantee can see this skill and may not take it away, and pretending it does not exist would
     * be a false statement about something already in their listing.
     *
     * @return the deleted skill's id, or empty when no such live skill is reachable
     * @throws NotPermittedException when the caller can see it and does not own it
     */
    public Optional<String> softDelete(String namespaceId, String name, Caller caller, String at) {
        Optional<Access> access = accessFor(namespaceId, name, caller);
        if (access.isEmpty()) {
            return Optional.empty();
        }
        if (!access.get().owns()) {
            throw new NotPermittedException("deleting '" + name + "' is its owner's to do; it takes "
                    + "the skill away from everyone reading it, and only the owner can answer for "
                    + "that");
        }
        return skills.softDelete(access.get().skillId(), at);
    }

    /**
     * Clears {@code deleted_at} on the skill this address names — <strong>the owner's only, and the
     * only by-address path that resolves a deleted row</strong> (ADR 0050).
     *
     * @return the restored skill's id, or empty when no deleted skill of this name the caller owns
     */
    public Optional<String> restore(String namespaceId, String name, Caller caller, String at) {
        return skills.restore(namespaceId, name, caller, at);
    }
}
