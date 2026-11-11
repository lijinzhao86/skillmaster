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
 * @param source the caller's relation to this skill — created / shared / joined, or null when the
 *             skill is visible only because it is public. It travels with the rung because both are
 *             computed by the same statement ({@code SkillPredicates.IDENTIFIED}); asking the
 *             library question separately would be a second answer that can disagree with the
 *             authorization this read already made
 * @param rung the effective rung — the higher of the grant and the public rung (ADR 0048), or null
 *             when the caller owns the skill and neither source applies
 * @param callerId who asked. Carried because discarding a draft asks whether <em>this person</em>
 *                 submitted it (ADR 0034 §决定 5), and that question is not about the rung
 * @param everPublished whether any version of this skill has ever gone live. The narrow delete's
 *                      condition (ADR 0127) reads it from M12's own row: the check and the delete
 *                      have to be one statement, and {@code skill_version} is M7's table
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
        String source,
        String currentVersionId,
        String offlineAt,
        String offlinePendingAt,
        String offlineDueAt,
        boolean owns,
        String rung,
        String callerId,
        boolean everPublished) {

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

    /**
     * Whether the skill is offline <em>now</em> — the switch, or a ramp whose date has passed even
     * if the daily job has not materialised it yet (v61, ADR 0184).
     *
     * <p><strong>One definition, because the read paths used to each write their own.</strong>
     * Every consumer decision that is really "is it serving" asks this; {@code offlineAt} alone
     * leaves the window between the due instant and the sweep reading as online, which is a day in
     * which copy worked while every read answered 503.
     */
    public boolean offlineNow() {
        return offlineAt != null
                || (offlineDueAt != null
                    && !com.skillmasterai.common.Timestamps.parse(offlineDueAt).isAfter(
                            java.time.Instant.now()));
    }

    /**
     * Still inside the ramp: serving with a warning, and not joinable (v61, ADR 0184). The mirror
     * of {@link #offlineNow()} — a due date that has passed is offline, never pending — so the two
     * answers cannot disagree between the due instant and the job that materialises it.
     */
    public boolean pendingNow() {
        return offlineAt == null && offlineDueAt != null && !offlineNow();
    }
}
