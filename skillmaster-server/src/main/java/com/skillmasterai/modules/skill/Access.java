package com.skillmasterai.modules.skill;

/**
 * What M12 knows about one skill and one caller: the address, the row's L1 projection, and the
 * rung the caller holds — already decided.
 *
 * <p><strong>This is the seam the content modules receive.</strong> M7 and M9 no longer read
 * {@code skill} or {@code skill_grant}; the use case asks M12 once ({@code accessFor}) and hands
 * the answer on. A content module therefore cannot re-ask the authorization question, and cannot
 * get a different answer the second time.
 *
 * <p><strong>The rung is the effective one</strong> — the higher of what was granted and what the
 * skill's public rung hands to everybody (ADR 0048). {@code owns} is kept apart because ownership
 * is above the ladder rather than on it: only the owner may delete (ADR 0047 §决定 4), and only
 * the owner may restore (ADR 0050).
 *
 * <p><strong>Reaching this type at all already means "may read".</strong> {@code accessFor} refuses
 * a row the caller has no relationship with, so the same empty result answers "no such skill" and
 * "not yours" — §4.1's one 404. What this type adds is <em>how far</em> the caller may go.
 *
 * @param currentVersionId the version consumers get, or null when nothing has been published yet.
 *                         An opaque id for M7: it resolves {@code latest} with it, and it is the
 *                         only way M7 learns what is current without reading {@code skill}
 * @param title/description/frontmatter the row's copy of the current published version (ADR 0031),
 *                         carried so the author page has something to show when no version is
 *                         selected — the empty-tree case ADR 0051 keeps apart from a 404
 * @param owns whether the skill is in the namespace the caller owns. Kept as a boolean because
 *             "mine" and "shared with me" survive past the lookup: nobody may discard a draft that
 *             is not theirs merely by holding a rung, and only the owner may delete
 * @param rung the effective rung — the higher of the grant and the public rung (ADR 0048), or null
 *             when the caller owns the skill and neither source applies
 * @param callerId who asked. Carried because discarding a draft asks whether <em>this person</em>
 *                 submitted it (ADR 0034 §决定 5), and that question is not about the rung
 */
public record Access(
        String skillId,
        String namespaceId,
        String name,
        String title,
        String description,
        String frontmatterJson,
        String visibility,
        String publicRung,
        String currentVersionId,
        String offlineAt,
        boolean owns,
        String rung,
        String callerId) {

    /**
     * Whether the caller is at or above {@code required}.
     *
     * <p>The owner is above the ladder rather than on it, so ownership answers yes to every rung —
     * including {@code admin}, which is why "may administer" and "owns" had to be separated at the
     * deletion path (ADR 0047 §决定 4).
     */
    public boolean atLeast(String required) {
        return owns || GrantRole.atLeast(rung, required);
    }
}
