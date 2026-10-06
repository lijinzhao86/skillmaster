package com.skillmasterai.usecase.model;

/**
 * What the submit use case hands back.
 *
 * <p>Flattened into primitives rather than holding M7's {@code SubmitOutcome}. The API layer is
 * allowed to see a module's public types, but a use case's result is the wrong place to make it
 * do so: this is the boundary the HTTP layer is written against, and keeping it free of module
 * types means a module can be rearranged without the controller noticing.
 *
 * <p>Nothing here says what is published. A submission leaves a draft and changes nothing the
 * consumption plane sees (ADR 0031), so the answer to "what is live" is not this object's to give.
 *
 * @param created whether this call created the version; false means identical content was already
 *                there, and {@code submittedAt} is when it first arrived
 * @param number  the skill's Nth distinct content (ADR 0012) — the {@code @N} form of its address
 * @param state   {@code draft} / {@code published} / {@code discarded} (ADR 0031). Carried rather
 *                than assumed because a replay answers with the row that already holds that digest,
 *                and nothing about a replay says which state that row is in
 */
public record SubmittedSkill(
        String skillId,
        String name,
        String namespaceSlug,
        int number,
        String digest,
        int fileCount,
        long totalBytes,
        String submittedAt,
        boolean created,
        String state) {
}
