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
 * @param skillCreated whether this call created the skill. Not derivable from {@code created}, which
 *                is also true for a new version of an old skill — and it is the only thing left that
 *                can tell a first submission from a later one, now that the version number has left
 *                the wire (ADR 0033)
 * @param version the version name the author declared, or null when they declared none — in which
 *                case {@code digest} is the only way to address it
 * @param state   {@code draft} / {@code published} / {@code revoked} / {@code discarded}
 *                (ADR 0031). Carried rather
 *                than assumed because a replay answers with the row that already holds that digest,
 *                and nothing about a replay says which state that row is in
 * @param dropped archive artefacts the door discarded before the version was built (v33). Reported
 *                rather than swallowed: a file that vanished without a word is the kind of silent
 *                degradation §3.3 forbids, and the author is the only one who can tell junk from
 *                something they meant to upload
 */
public record SubmittedSkill(
        String skillId,
        String name,
        String namespaceSlug,
        String version,
        String draftId,
        String draftName,
        String note,
        String digest,
        int fileCount,
        long totalBytes,
        String submittedAt,
        boolean created,
        boolean skillCreated,
        String state,
        int number,
        java.util.List<String> dropped) {

    public SubmittedSkill {
        dropped = java.util.List.copyOf(dropped);
    }
}
