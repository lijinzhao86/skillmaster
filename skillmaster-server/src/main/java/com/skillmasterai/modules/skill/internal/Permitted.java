package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.modules.skill.GrantRole;

/**
 * One skill as the caller may see it, and how far they may go.
 *
 * <p>Its own type since v37: the record and its javadoc outgrew {@link SkillRepository}'s file, and
 * every authorization path carries this value.
 *
 * @param owns whether the skill is in the namespace the caller owns
 * @param rung the effective rung — the higher of the grant and the public rung (ADR 0048), or null
 *             when the caller owns the skill and neither source applies
 * @param source the caller's relation to this skill (created / shared / joined), or null when it is
 *               visible only because it is public. Computed by the same {@code IDENTIFIED} CTE, so
 *               it cannot disagree with the rung beside it
 */
public record Permitted(SkillRepository.SkillRow skill, boolean owns, String rung, String source) {

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
