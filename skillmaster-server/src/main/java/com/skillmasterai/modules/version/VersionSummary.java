package com.skillmasterai.modules.version;

/**
 * One row of a skill's version list, without the manifest.
 *
 * <p>The shape a person chooses between versions with, which is why it carries {@code isCurrent} and
 * none of the metadata: the question it answers is "which of these do I want", not "what is in this
 * one". Reading one is {@link SkillSnapshot}'s job.
 *
 * @param digest    the version's identity; the number is an alias for it (ADR 0012)
 * @param state     draft, published or discarded — what the author has and has not decided about it
 * @param stateAt   when it left draft. Null while it is a draft, which is every version that has just
 *                  been submitted
 * @param isCurrent whether the skill's pointer names it, which is the one consumers get. False for a
 *                  superseded version even though that version is still published and still
 *                  addressable by its own number
 */
public record VersionSummary(int number, String digest, int fileCount, long totalBytes,
        String submittedAt, String state, String stateAt, boolean isCurrent) {
}
