package com.skillmasterai.usecase.model;

/**
 * One of the caller's own skills, as the author's list needs it.
 *
 * <p>Flattened for the same reason {@link SubmittedSkill} is: this is the boundary the HTTP layer is
 * written against, and keeping module types out of it means a module can be rearranged without the
 * controller noticing. The two facts that only exist on this side of ADR 0031 are the last two — how
 * much is waiting, and whether anything is published at all.
 *
 * @param currentNumber the published version's alias, or null when nothing is published yet. Null
 *                      rather than zero, because version 0 does not exist and a number is an address
 * @param currentDigest its digest, or null in the same case
 * @param draftCount    versions waiting to be published or discarded
 * @param draftNumber   the highest-numbered one of those, or null when there are none. It travels
 *                      with the count because the count is what a listing shows and the number is
 *                      what it links to — a reader who sees "1 个待上线" and clicks it should arrive
 *                      at that version, not at the skill and then have to find it
 * @param latestSubmittedAt RFC3339 UTC of the most recent submission that has not been discarded —
 *                          what the listing is ordered by, and what makes an author's just-submitted
 *                          skill the first thing they see
 */
public record AuthoredSkillSummary(
        String namespaceSlug,
        String name,
        String title,
        String description,
        String visibility,
        Integer currentNumber,
        String currentDigest,
        int draftCount,
        Integer draftNumber,
        String latestSubmittedAt) {
}
