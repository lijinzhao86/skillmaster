package com.skillmasterai.usecase;

/**
 * The facts a completed content read contributes to usage history (v58).
 *
 * <p>Produced only when the lookup found the file: a resolved address whose manifest lacks the
 * relpath is a 404, not a read someone performed. The owner comes from the namespace the read
 * already resolved, so recording never re-asks M4.
 */
public record SkillConsumption(
        String skillId,
        String ownerUserId,
        String versionId,
        String versionLabel,
        String address) {
}
