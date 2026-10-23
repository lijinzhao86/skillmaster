package com.skillmasterai.modules.skill;

/**
 * Where a visible skill stands relative to the caller's library and its tag writes (v14).
 *
 * <p>Public because it crosses the module boundary: the use-case layer decides what a refusal means,
 * and it needs these facts without reaching into M12's internal package. The record carries no SQL
 * and no behaviour beyond the two derived answers, so exposing it costs nothing structurally.
 *
 * @param source      created / shared / joined, or null when the skill is visible only because it
 *                    is public — the one state in which joining means something
 * @param active      whether the caller's library entry is effective (ADR 0082)
 * @param discoverable whether it may be joined right now: public, published, online, not deleted
 * @param grantRole   the grant held, or null
 * @param publicRung  the rung the skill hands to everyone, or null
 */
public record SkillStanding(String skillId, String namespaceId, String name, String visibility,
        String publicRung, String grantRole, String source, boolean owns, boolean active,
        boolean discoverable, String offlineAt, String currentVersionId) {

    /** The effective rung: the higher of the grant and the public rung (ADR 0048). */
    public String rung() {
        return GrantRole.higher(grantRole, publicRung);
    }

    /** Owner or {@code admin}: the two standings that may change a skill's category and tags. */
    public boolean mayOrganize() {
        return owns || GrantRole.atLeast(rung(), GrantRole.ADMIN);
    }

    public boolean inLibrary() {
        return source != null;
    }
}
