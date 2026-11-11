package com.skillmasterai.usecase.model;

import java.util.List;

/**
 * One skill as both listings show it (v14).
 *
 * <p><strong>The transport shape, assembled in the use case and mapped to JSON in the controller.</strong>
 * The two scopes — the library and the public search — produce the same fields from two different
 * queries; keeping the shape here rather than in either module is what makes the controller's
 * mapping one thing instead of two branches (TD §接口).
 *
 * @param source     created / shared / joined, or null when the caller has no relationship with the
 *                   skill. Nullable on purpose: a discovery result the caller never added is a
 *                   result, and its card simply has no source to show
 * @param state      live / draft / offline / deleted, derived from the predicates, never stored
 * @param myTags     only while the library entry is effective; a dormant entry's private tags are
 *                   not part of the card at all
 */
public record SkillCardView(
        String id,
        String namespace,
        String name,
        String description,
        String whenToUse,
        /** The caller's own sentence, or null. Only an effective library entry carries one. */
        String myWhenToUse,
        /**
         * Whether this result was promoted by the personal layer (S1/S2). Null outside a search,
         * so a listing card does not claim a match that was never evaluated.
         */
        Boolean myWhenToUseMatched,
        String source,
        /**
         * The caller's own rung; null when they own it, and null when the caller has no
         * relationship to the skill (a discovery card) — "your rung" is a library fact. (v35 —
         * the console leaves the chip off either way.)
         */
        String standing,
        String state,
        String version,
        String updatedAt,
        String deletedAt,
        /** The 下线中 ramp's due date while it is ahead, or null (v61, ADR 0184). */
        String offlinePendingUntil,
        Category category,
        List<PublicTag> publicTags,
        List<MyTag> myTags) {

    public SkillCardView {
        publicTags = List.copyOf(publicTags);
        myTags = List.copyOf(myTags);
    }

    public SkillCardView withMyWhenToUseMatched(boolean matched) {
        return new SkillCardView(id, namespace, name, description, whenToUse, myWhenToUse, matched,
                source, standing, state, version, updatedAt, deletedAt, offlinePendingUntil,
                category, publicTags, myTags);
    }

    public record Category(String key, String name) {
    }

    public record PublicTag(String key, String name) {
    }

    public record MyTag(String id, String name) {
    }
}
