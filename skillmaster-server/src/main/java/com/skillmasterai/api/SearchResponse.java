package com.skillmasterai.api;

import com.skillmasterai.modules.search.SkillSearchService;
import com.skillmasterai.usecase.SkillListing;
import java.util.List;

/**
 * §4.2's listing body: L1 fields and a cursor.
 *
 * <p>{@code next_cursor} is the whole pagination contract — a value means there is another page,
 * and {@code null} means this was the last. It is written as an explicit {@code null} rather than
 * omitted, which is what §4.2's example shows; the two say the same thing to a client and only one
 * of them can be documented. No total count accompanies it, because a count over a table being
 * written to is stale the moment it is computed, and a client that branches on it will one day
 * fetch a page that is not there.
 */
public record SearchResponse(List<Card> skills, String nextCursor) {

    /**
     * One row of the listing: L1 and nothing else (§4.2).
     *
     * <p><strong>This is the layer every skill is loaded for, so the fields it carries are a budget
     * rather than a schema.</strong> {@code title}, {@code visibility}, {@code updatedAt} and the
     * {@code version} pair were here and are gone: nothing read them. The title — which is not a
     * field of the Agent Skills specification, is not one Claude Code recognises, and falls back to
     * the name — was making the listing print a skill's name twice for every author who had not
     * declared one; it still exists on the author's own plane, where a person reads it. The version
     * pair was the last to go and is the one worth knowing about: it was there so that a single
     * search was enough to pin, and since ADR 0035 the client resolves the version and remembers it
     * itself, so the pin never has to travel on the card.
     *
     * @param whenToUse the author's {@code when_to_use}, or null. Carried because the listing is read
     *                  to answer "is this the skill I want", and that field is the author's own answer
     *                  to it. Claude Code joins the two for its listing ({@code description -
     *                  when_to_use}); the join is the reader's, so that a client that only wants the
     *                  description still has it
     */
    public record Card(
            String id,
            String name,
            String description,
            String whenToUse,
            String namespace) {
    }

    /**
     * @param listing the page plus the id-to-slug map for the namespaces it turned out to hold.
     *                The slug is M4's column and a page can span namespaces since ADR 0034, so the
     *                composition layer supplies it rather than this record reaching for it
     */
    public static SearchResponse of(SkillListing listing) {
        SkillSearchService.SearchPage page = listing.page();
        return new SearchResponse(
                page.skills().stream()
                        .map(card -> new Card(
                                card.id(),
                                card.name(),
                                card.description(),
                                card.whenToUse(),
                                listing.namespaceSlugs().getOrDefault(card.namespaceId(), card.namespaceId())))
                        .toList(),
                page.nextCursor());
    }
}
