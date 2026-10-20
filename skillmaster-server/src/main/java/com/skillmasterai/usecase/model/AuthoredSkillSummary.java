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
 * @param updatedAt RFC3339 UTC of the current published version's submission, or of the newest draft
 *                  for a skill nothing has been published from. This is the card's "更新于" and what
 *                  the listing is ordered by. Null when every version was discarded — the row still
 *                  exists and the page says so
 * @param deletedAt RFC3339 UTC of the soft delete, or null on the live list. The deleted list shows
 *                  "删除于 …" instead (ADR 0050)
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
        String updatedAt,
        String deletedAt,
        String offlineAt) {
}
