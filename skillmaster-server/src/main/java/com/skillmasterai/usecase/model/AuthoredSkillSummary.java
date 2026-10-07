package com.skillmasterai.usecase.model;

/**
 * One of the caller's own skills, as the author's list needs it.
 *
 * <p>Flattened for the same reason {@link SubmittedSkill} is: this is the boundary the HTTP layer is
 * written against, and keeping module types out of it means a module can be rearranged without the
 * controller noticing. The two facts that only exist on this side of ADR 0031 are the last two — how
 * much is waiting, and whether anything is published at all.
 *
 * @param currentVersion the published version's name, or null when nothing is published yet — or
 *                      when the published version's author declared no name. Null rather than a
 *                      placeholder, because a version that names nothing is not one a client can
 *                      fetch by that name
 * @param currentDigest its digest, or null in the first of those cases
 * @param draftCount    versions waiting to be published or discarded
 * @param draftVersion  the most recently submitted one of those, or null when there are none. It
 *                      travels with the count because the count is what a listing shows and the
 *                      version is what it links to — a reader who sees "1 个待上线" and clicks it
 *                      should arrive at that version, not at the skill and then have to find it
 * @param draftDigest   that draft's digest, null in the same case, and the half that makes even a
 *                      nameless draft linkable (ADR 0033)
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
        String currentVersion,
        String currentDigest,
        int draftCount,
        String draftVersion,
        String draftDigest,
        String latestSubmittedAt) {
}
