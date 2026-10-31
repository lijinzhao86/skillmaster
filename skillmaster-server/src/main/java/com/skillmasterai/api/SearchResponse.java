package com.skillmasterai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.skillmasterai.usecase.SkillListing;
import com.skillmasterai.usecase.model.SkillCardView;
import java.util.List;

/**
 * §4.2's listing body: L1 fields, the v14 organization fields, and a cursor.
 *
 * <p>{@code next_cursor} is the whole pagination contract — a value means there is another page,
 * and {@code null} means this was the last. It is written as an explicit {@code null} rather than
 * omitted, which is what §4.2's example shows; the two say the same thing to a client and only one
 * of them can be documented. No total count accompanies it, because a count over a table being
 * written to is stale the moment it is computed, and a client that branches on it will one day
 * fetch a page that is not there.
 */
public record SearchResponse(List<Card> skills, String nextCursor, String note) {

    /**
     * @param note why the answer is not what was asked for — the one case today is semantic search
     *             falling back to keyword (v19). Null on every ordinary answer; a client that sees
     *             one should say so rather than present it as a plain result
     */
    public SearchResponse {
        skills = List.copyOf(skills);
    }

    /**
     * One row of the listing (v14).
     *
     * <p><strong>The card is the layer every skill is loaded for, so these fields are a budget.</strong>
     * They are here because both listings — the library by API and web, and the public search —
     * show the same card, and because the console's "我的 skill" and "发现" pages read the same
     * transport shape. {@code source} is null for a discovery result the caller never added;
     * {@code my_tags} is empty unless the library entry is effective.
     *
     * @param whenToUse the author's {@code when_to_use}, or null
     * @param state     live / draft / offline / deleted
     * @param deletedAt the soft-delete time, or null on the live list
     */
    public record Card(
            String id,
            String namespace,
            String name,
            String description,
            String whenToUse,
            String source,
            // The caller's own rung on this skill; null when they own it, and null when they have no
            // relationship to it at all (a discovery card — "your rung" is a library fact). Omitted
            // rather than sent as null: the console draws the chip only when there is something to
            // draw (v35).
            @JsonInclude(JsonInclude.Include.NON_NULL) String standing,
            String state,
            String version,
            String updatedAt,
            String deletedAt,
            Category category,
            List<PublicTag> publicTags,
            List<MyTag> myTags,
            // Omitted when there is no score, rather than sent as null: the card is the layer every
            // skill is loaded for, and a keyword hit has no similarity to report. A client cannot
            // mistake "absent" for "zero" here, because zero is a value the ranking really produces.
            @JsonInclude(JsonInclude.Include.NON_NULL) Double score) {

        public Card {
            publicTags = List.copyOf(publicTags);
            myTags = List.copyOf(myTags);
        }

        public static Card of(SkillCardView card) {
            return of(card, null);
        }

        /**
         * @param score the semantic ranking's similarity, or null on the keyword path. Nullable
         *              rather than 0: a keyword hit has no similarity, and inventing one would let
         *              a client sort two kinds of answer on a number only one of them has
         */
        public static Card of(SkillCardView card, Double score) {
            return new Card(card.id(), card.namespace(), card.name(), card.description(),
                    card.whenToUse(), card.source(), card.standing(), card.state(),
                    card.version(),
                    card.updatedAt(), card.deletedAt(),
                    card.category() == null ? null
                            : new Category(card.category().key(), card.category().name()),
                    card.publicTags().stream()
                            .map(tag -> new PublicTag(tag.key(), tag.name())).toList(),
                    card.myTags().stream()
                            .map(tag -> new MyTag(tag.id(), tag.name())).toList(),
                    score);
        }
    }

    public record Category(String key, String name) {
    }

    public record PublicTag(String key, String name) {
    }

    public record MyTag(String id, String name) {
    }

    public static SearchResponse of(SkillListing listing) {
        return new SearchResponse(
                listing.cards().stream().map(Card::of).toList(),
                listing.page().nextCursor(),
                null);
    }

    /** The public keyword answer with a note attached (v19): what was searched, and why it was not semantic. */
    public static SearchResponse of(SkillListing listing, String note) {
        return new SearchResponse(
                listing.cards().stream().map(Card::of).toList(), listing.page().nextCursor(), note);
    }

    /** For the library listing, which builds its cards the same way but pages by offset or cursor. */
    public static SearchResponse of(List<SkillCardView> cards, String nextCursor) {
        return new SearchResponse(cards.stream().map(Card::of).toList(), nextCursor, null);
    }

    /**
     * The semantic answer (v19): no cursor — a ranking by similarity has no stable page boundary to
     * carry — and a note only when the answer is a fallback.
     */
    public static SearchResponse semantic(List<com.skillmasterai.usecase.SemanticSearchUseCase.Hit> hits) {
        return new SearchResponse(
                hits.stream().map(hit -> Card.of(hit.card(), hit.score())).toList(), null, null);
    }

    /** Keyword results, with the reason semantic search did not run (v19). */
    public static SearchResponse of(List<SkillCardView> cards, String nextCursor, String note) {
        return new SearchResponse(cards.stream().map(Card::of).toList(), nextCursor, note);
    }
}
