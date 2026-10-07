package com.skillmasterai.modules.search;

/**
 * One row of §4.2's search result: L1, and nothing else.
 *
 * <p>No file list, no body, no content. That is the first gate of progressive loading (§4.2), and
 * it is why {@code description} is here at all — the whole point of a listing is that an agent can
 * judge relevance from it without fetching anything.
 *
 * <p><strong>Five fields used to be here and are not: {@code title}, {@code visibility},
 * {@code updatedAt}, {@code version} and {@code digest}.</strong> The card is the layer that is
 * loaded for <em>every</em> skill, so what it carries is a budget rather than a schema — and none of
 * the five was read by anything. The title was the interesting one: it is not a field of the Agent
 * Skills specification, it is not one Claude Code recognises, and it falls back to the name when an
 * author declares none, so the listing was printing the name twice. It still exists where a person
 * reads it, on the author's own plane. {@code updatedAt} stays on the row below because the ordering
 * sorts on it; it is simply not something an agent judging relevance needs. The version pair went
 * last: it was there so that one search was enough to pin, and since ADR 0035 the client resolves and
 * remembers the version itself.
 *
 * @param whenToUse the author's `when_to_use`, or null. **The L1's second half**, and the reason for
 *                  it being here: a listing is read to decide whether a skill is the one to look at,
 *                  and "when to use this" is exactly that judgement. Claude Code's own listing joins
 *                  the two — `description - when_to_use` — and this is the same text for the same
 *                  reason.
 * @param updatedAt RFC3339 UTC. It is not the version's timestamp — a metadata edit moves this
 *                  without creating a version (§4.3), and it is the field the ordering sorts on
 */
public record SkillCard(
        String id,
        String name,
        String description,
        String whenToUse,
        String namespaceId,
        String updatedAt) {
}
