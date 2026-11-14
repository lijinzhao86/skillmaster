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
            // v59: the caller's own sentence, only for an effective library entry, and whether
            // this result was promoted by it. Both are omitted when absent so a public card's
            // shape does not change.
            @JsonInclude(JsonInclude.Include.NON_NULL) String myWhenToUse,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean myWhenToUseMatched,
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
            /** v61: the 下线中 ramp's due date while it is ahead, omitted otherwise. */
            @JsonInclude(JsonInclude.Include.NON_NULL) String offlinePendingUntil,
            Category category,
            List<PublicTag> publicTags,
            List<MyTag> myTags,
            /** v65: the platform's one-line expected result; omitted when absent. */
            @JsonInclude(JsonInclude.Include.NON_NULL) String outcome,
            /** v65: displayed attributes, short names only; omitted when the skill has none. */
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Label> labels,
            // Omitted when there is no score, rather than sent as null: the card is the layer every
            // skill is loaded for, and a keyword hit has no similarity to report. A client cannot
            // mistake "absent" for "zero" here, because zero is a value the ranking really produces.
            @JsonInclude(JsonInclude.Include.NON_NULL) Double score,
            /* v49: the compact selection signals. The full origin/license blocks live on
               /metadata; a list carries only what a reader needs to choose a row. */
            @JsonInclude(JsonInclude.Include.NON_NULL) String originLabel,
            @JsonInclude(JsonInclude.Include.NON_NULL) String originKind,
            @JsonInclude(JsonInclude.Include.NON_NULL) String originLocator,
            @JsonInclude(JsonInclude.Include.NON_NULL) String originUrl,
            @JsonInclude(JsonInclude.Include.NON_NULL) String licenseSpdx,
            @JsonInclude(JsonInclude.Include.NON_NULL) String licenseStatus,
            @JsonInclude(JsonInclude.Include.NON_NULL) String usageBucket,
            @JsonInclude(JsonInclude.Include.NON_NULL) String callsBucket,
            @JsonInclude(JsonInclude.Include.NON_NULL) String lastUsed,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer joins) {

        public Card {
            publicTags = List.copyOf(publicTags);
            myTags = List.copyOf(myTags);
            labels = labels == null ? List.of() : List.copyOf(labels);
        }

        public static Card of(SkillCardView card) {
            return of(card, null);
        }

        /**
         * @param score the ranking metric the hit was ordered by: a cosine similarity on a
         *              semantic tier (0–1), or the exact run's length on S0/S1/S3 (an identity
         *              score, not a similarity). Null on the keyword path. Nullable rather than 0:
         *              a keyword hit has no score, and inventing one would let a client sort two
         *              kinds of answer on a number only one of them has. A client that needs the
         *              tier itself should use the CLI's marker or compare ranking, not this field.
         */
        public static Card of(SkillCardView card, Double score) {
            return new Card(card.id(), card.namespace(), card.name(), card.description(),
                    card.whenToUse(), card.myWhenToUse(), card.myWhenToUseMatched(),
                    card.source(), card.standing(), card.state(),
                    card.version(),
                    card.updatedAt(), card.deletedAt(), card.offlinePendingUntil(),
                    card.category() == null ? null
                            : new Category(card.category().key(), card.category().name()),
                    card.publicTags().stream()
                            .map(tag -> new PublicTag(tag.key(), tag.name())).toList(),
                    card.myTags().stream()
                            .map(tag -> new MyTag(tag.id(), tag.name())).toList(),
                    card.outcome(),
                    card.labels().stream()
                            .map(label -> new Label(label.key(), label.value(), label.label(),
                                    label.full(), label.group(), label.filterable()))
                            .toList(),
                    score, null, null, null, null, null, null, null, null, null, null);
        }

        public Card withSignals(String originLabel, String originKind, String originLocator,
                String originUrl, String licenseSpdx, String licenseStatus, String usageBucket,
                String callsBucket, String lastUsed, Integer joins) {
            return new Card(id, namespace, name, description, whenToUse, myWhenToUse,
                    myWhenToUseMatched, source, standing, state,
                    version, updatedAt, deletedAt, offlinePendingUntil, category, publicTags,
                    myTags, outcome, labels, score,
                    originLabel, originKind, originLocator, originUrl, licenseSpdx, licenseStatus,
                    usageBucket, callsBucket, lastUsed, joins);
        }
    }

    public record Category(String key, String name) {
    }

    public record PublicTag(String key, String name) {
    }

    public record MyTag(String id, String name) {
    }

    /**
     * v65: one displayed platform attribute. {@code full} is for the CLI, not the console capsule;
     * {@code filterable} tells the interface whether the word may be drawn as a filter link.
     */
    public record Label(String key, String value, String label, String full, String group,
            boolean filterable) {
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
