package com.skillmasterai.api;

import com.skillmasterai.usecase.model.AuthoredSkillSummary;
import java.util.List;

/**
 * The author's own listing body (ADR 0031).
 *
 * <p>Looks like {@link SearchResponse} and is not it. There is no cursor, because nothing here is
 * paged; no score, because nothing ranked it; and no {@code updatedAt}, because the field a person
 * wants on this page is the opposite one — {@code latestSubmittedAt} is when work arrived, which is
 * what the list is ordered by, where the consumption plane's timestamp moves only when something is
 * published.
 *
 * <p>{@code current} is null for a skill nothing has been published from, which is the ordinary state
 * of one that was just submitted. Null rather than a placeholder, because a version that names
 * nothing is not one a client could try to fetch.
 *
 * <p>Both {@code current} and {@code draft} carry a name <em>and</em> a digest, and either name may
 * be null: a version whose author declared no {@code version} has no name and is addressable only by
 * its digest (ADR 0033). Carrying the digest beside the name is what keeps such a version linkable
 * rather than merely visible.
 *
 * <p>No {@code uri}. The consumption plane's cards carry pinned addresses because following them is
 * how a client reads content; this listing's only destination is a page in the SPA, whose routes are
 * the SPA's own to spell, and a server that also emitted them would be a second place they are
 * written down.
 *
 * <p>{@code namespace} is on every row, and since ADR 0034 the rows no longer share one: this listing
 * holds the skills shared with the caller as an {@code editor} too, so the namespace is a property of
 * the row rather than the caller's own repeated. That is the same choice {@link SearchResponse.Card}
 * makes, and now for a sharper reason than it had before — a client that filled the field in from the
 * session would give a shared skill an address it does not have, which resolves to a different skill
 * of the caller's or to none at all.
 *
 * @param drafts      how many versions are waiting to be published or discarded — the one thing this
 *                    page has to say that no other listing does
 * @param draft       the most recently submitted one of those, or null when there are none. The
 *                    count is what the row says; this is what it links to, so that clicking
 *                    "1 个待上线" arrives at that version rather than at the skill
 */
public record AuthoredSkillsResponse(List<Skill> skills) {

    public record Skill(
            String namespace,
            String name,
            String title,
            String description,
            String visibility,
            Version current,
            int drafts,
            Version draft,
            String latestSubmittedAt) {
    }

    /** @param name the version's name, or null when its author declared none (ADR 0033) */
    public record Version(String name, String digest) {
    }

    public static AuthoredSkillsResponse of(List<AuthoredSkillSummary> summaries) {
        return new AuthoredSkillsResponse(summaries.stream()
                .map(summary -> new Skill(
                        summary.namespaceSlug(),
                        summary.name(),
                        summary.title(),
                        summary.description(),
                        summary.visibility(),
                        summary.currentDigest() == null ? null
                                : new Version(summary.currentVersion(),
                                        // Storage keeps the bare hex ADR 0005's formula produces; the
                                        // API presents it prefixed, on both planes and in the same
                                        // spelling.
                                        "sha256:" + summary.currentDigest()),
                        summary.draftCount(),
                        summary.draftDigest() == null ? null
                                : new Version(summary.draftVersion(),
                                        "sha256:" + summary.draftDigest()),
                        summary.latestSubmittedAt()))
                .toList());
    }
}
