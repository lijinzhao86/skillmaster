package com.skillmasterai.modules.skill;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.common.SkillTextFields;
import com.skillmasterai.modules.skill.internal.SkillRelationshipQueries;
import com.skillmasterai.modules.skill.internal.SkillRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

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

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SkillRepository skills;
    private final SkillRelationshipQueries relationships;

    public SkillMetadataService(SkillRepository skills, SkillRelationshipQueries relationships) {
        this.skills = skills;
        this.relationships = relationships;
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
    /**
     * The same row, with the library and tag facts v14's writes ask for (ADR 0082/0083).
     *
     * <p>Separate from {@link #accessFor} on purpose: {@code Access} is the author plane's shape and
     * carries the content facts that plane reads. This one carries the library's source and the
     * entry's effectiveness, and is only ever asked by the organize use cases.
     */
    public Optional<SkillStanding> standing(String namespaceId, String name, Caller caller) {
        return skills.standing(namespaceId, name, caller);
    }

    /**
     * The id behind an address, for callers that only need identity — today, the search exclusion
     * (v30).
     *
     * <p>Unlike {@link #accessFor} it asks nothing about the caller: the id is only used to drop
     * rows from a candidate set, and an id the caller cannot see matches nothing. A soft-deleted row
     * still resolves; it is not in any candidate set either. Empty means "no such skill", which the
     * caller treats as an empty exclusion rather than an error.
     */
    public Optional<String> idOf(String namespaceId, String name) {
        return skills.findAnyByName(namespaceId, name)
                .map(SkillRepository.AnyRow::id);
    }

    /**
     * The addresses behind a page of ids — one read for a whole notification page.
     *
     * <p>M14 stores only {@code skill_id}; the response needs {@code namespace/name}. Asking per
     * row would make the round trips proportional to the page, which is the shape this module has
     * avoided for grants and handles too.
     */
    public java.util.Map<String, SkillAddress> addressesOf(
            java.util.Collection<String> skillIds) {
        return relationships.addressesOf(skillIds);
    }

    /**
     * Which of these skills the caller may read right now.
     *
     * <p>This is what makes a notification's 「查看」 honest: an invitation accepted, a request
     * approved, or a grant revoked since the page loaded all change the answer. It asks the same
     * predicate every other read asks ({@code SkillPredicates.VISIBLE}), not a second copy.
     */
    public java.util.Set<String> readableAmong(Caller caller,
            java.util.Collection<String> skillIds) {
        return relationships.readableAmong(caller, skillIds);
    }

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
                    permitted.source(),
                    row.currentVersionId(),
                    row.offlineAt(),
                    permitted.owns(),
                    permitted.rung(),
                    caller.userId(),
                    row.everPublishedAt() != null);
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
    /**
     * Stops serving the skill, or resumes it. Nothing about versions changes.
     *
     * @param at the moment it went offline, or null to serve again
     */
    public void setOffline(String skillId, String at) {
        skills.setOffline(skillId, at);
    }

    /**
     * The pointer's current value, re-read inside the caller's transaction.
     *
     * <p>M7's revoke guards against taking down the current version, and its guard is computed from
     * an {@code Access} snapshot. Re-reading after the write closes the window where a rollback
     * commits in between; empty means "no pointer", which is not the revoked row's id.
     */
    public Optional<String> currentVersionId(String skillId) {
        return skills.currentVersionId(skillId);
    }

    public Optional<String> findOrCreate(String namespaceId, SkillMetadata metadata,
            String createdBy, String at) {
        return skills.findOrCreate(namespaceId, metadata, createdBy, at);
    }

    /**
     * Creates the skill, or refuses with the reason the name cannot be used (ADR 0070).
     *
     * <p><strong>Create-only, and that is why it is separate from {@link #findOrCreate}.</strong>
     * A submission wants "this row, whoever created it"; a create wants "nobody has this name".
     * The two would answer differently on the same input, so they are two methods rather than a
     * flag.
     *
     * <p>The conflict is re-read to say which one it is: a live skill is 409
     * {@code skill_already_exists}, a soft-deleted one is 409 {@code skill_deleted} — the latter's
     * only way out is restoring, which happens in the browser (ADR 0050, ADR 0071).
     *
     * @return the new skill's id
     * @throws com.skillmasterai.common.ConflictException with one of those two codes
     */
    public String createOwned(String namespaceId, SkillMetadata metadata, String createdBy,
            String at) {
        Optional<String> inserted = skills.insertNew(namespaceId, metadata, createdBy, at);
        if (inserted.isPresent()) {
            return inserted.get();
        }
        SkillRepository.AnyRow existing = skills.findAnyByName(namespaceId, metadata.name())
                .orElseThrow(() -> new IllegalStateException("skill '" + metadata.name()
                        + "' refused the insert but has no row"));
        if (existing.deletedAt() != null) {
            throw new com.skillmasterai.common.ConflictException(
                    com.skillmasterai.common.ErrorCode.SKILL_DELETED,
                    "the name '" + metadata.name() + "' belongs to a deleted skill; restore it"
                            + " before creating a skill with this name");
        }
        throw new com.skillmasterai.common.ConflictException(
                com.skillmasterai.common.ErrorCode.SKILL_ALREADY_EXISTS,
                "a skill named '" + metadata.name() + "' already exists");
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
     * @return whether the pointer actually moved. Zero means either that it already named this
     *         version — the loser of a race, or a second click on 上线 — or that the skill row was
     *         deleted underneath this publish; {@link #skillDeleted} tells them apart, and the
     *         deleted case must roll the transaction back (ADR 0127)
     */
    public boolean recordPublish(String skillId, VersionProjection version, DraftProjection drafts,
            String at) {
        requirePublishableText(version);
        return skills.recordPublish(skillId, version, drafts, at);
    }

    /**
     * Re-applies the write-time text contract to the stored version (ADR 0181).
     *
     * <p><strong>Why publish checks again.</strong> A draft can be older than the rule that now
     * governs it; without this, a value written before the ceiling existed would go live and only
     * a later edit would fail. The receipt names the same field paths the write path does.
     */
    private static void requirePublishableText(VersionProjection version) {
        Map<String, Object> frontmatter;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = version.frontmatterJson() == null ? Map.of()
                    : JSON.readValue(version.frontmatterJson(), Map.class);
            frontmatter = parsed;
        } catch (RuntimeException e) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "这一版存储的 frontmatter 无法解析，不能发布",
                    List.of(new ApiError.Detail("frontmatter", "unreadable")));
        }
        // The stored frontmatter is the author's source; the version's description column is the
        // fallback for rows written before this field was separated.
        Object rawDescription = frontmatter.getOrDefault("description", version.description());
        SkillTextFields.Report report = SkillTextFields.inspect(rawDescription, frontmatter);
        if (!report.problems().isEmpty()) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "这一版不能发布：" + report.problems().getFirst().message()
                            + " 回草稿改 SKILL.md 后再发布。",
                    report.problems().stream().map(SkillTextFields.Problem::detail).toList());
        }
    }

    /**
     * Whether the skill row is soft-deleted — the fact that tells a zero-row {@link #recordPublish}
     * apart from "the pointer already named this version" (v37, ADR 0127).
     */
    public boolean skillDeleted(String skillId) {
        return skills.deleteState(skillId).deleted();
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

    /** What a narrow delete did, or why it did nothing (v37, ADR 0127). */
    public enum DeleteOutcome { DELETED, NOT_FOUND, NOT_OWNER, PUBLISHED }

    /**
     * The answer plus the id it concerns: the id is what the audit row names, and reading it again
     * after the event would be a second lookup that could disagree (ADR 0004).
     */
    public record DeleteResult(DeleteOutcome outcome, String skillId) {
    }

    /**
     * Soft-deletes a skill that has never had a version go live (v37, ADR 0127).
     *
     * <p><strong>Ownership first, then one conditional statement.</strong> The first two answers are
     * the ones the product's 404 rule needs (nothing, or not yours — the same empty result); the
     * third is decided by the delete itself, so a publish that lands between the read and the write
     * turns into a refusal rather than a deletion. The follow-up read only explains which refusal.
     */
    public DeleteResult deleteNeverPublished(String namespaceId, String name, Caller caller,
            String at) {
        Optional<Access> access = accessFor(namespaceId, name, caller);
        if (access.isEmpty()) {
            return new DeleteResult(DeleteOutcome.NOT_FOUND, null);
        }
        String skillId = access.get().skillId();
        if (!access.get().owns()) {
            return new DeleteResult(DeleteOutcome.NOT_OWNER, skillId);
        }
        if (skills.softDeleteNeverPublished(skillId, at).isPresent()) {
            return new DeleteResult(DeleteOutcome.DELETED, skillId);
        }
        return new DeleteResult(
                skills.deleteState(skillId).deleted() ? DeleteOutcome.NOT_FOUND
                        : DeleteOutcome.PUBLISHED,
                skillId);
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
