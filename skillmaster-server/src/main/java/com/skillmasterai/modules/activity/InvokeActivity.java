package com.skillmasterai.modules.activity;

/**
 * One skill invocation: the body was resolved and its bytes are the answer (v58).
 *
 * <p>Attribution happens here, before the row is written: the recorder looks back at most 30
 * minutes for the newest search by the same account whose results contained this skill. A hit
 * writes the search's id and the skill's original rank; a miss writes a direct call.
 *
 * @param ownerUserId resolved from the skill's namespace, so the owner view never has to join
 * @param versionLabel the version as the address spells it; a snapshot, not a live lookup
 * @param at          RFC3339 UTC, whole seconds
 */
public record InvokeActivity(
        String actorUserId,
        String ownerUserId,
        String skillId,
        String skillAddress,
        String versionId,
        String versionLabel,
        String at) {
}
