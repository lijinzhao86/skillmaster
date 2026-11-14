package com.skillmasterai.modules.skill;

import java.util.List;

/**
 * The narrowing a search carries besides its scope (v19), already resolved to keys.
 *
 * <p><strong>Resolved by the caller, because resolving is a question about M12's tables.</strong>
 * A category arrives as a name a person typed, a public tag as a word, a private tag as a word the
 * caller owns; all three become keys or ids through the same readers the keyword path uses. Doing
 * that here would put name parsing inside a query builder.
 *
 * @param categoryKeys  "any of" — a skill sits on one shelf, so two shelves widen
 * @param publicTagKeys "all of" — each key becomes its own EXISTS
 * @param myTagIds      "all of", addressed by definition id; the owner is checked in the clause
 * @param labelDefinitionIds "all of" (v65), addressed by definition id; each id is one EXISTS
 */
public record SearchFilters(List<String> categoryKeys, List<String> publicTagKeys,
        List<String> myTagIds, List<String> labelDefinitionIds) {

    public SearchFilters(List<String> categoryKeys, List<String> publicTagKeys,
            List<String> myTagIds) {
        this(categoryKeys, publicTagKeys, myTagIds, List.of());
    }

    public SearchFilters {
        categoryKeys = categoryKeys == null ? List.of() : List.copyOf(categoryKeys);
        publicTagKeys = publicTagKeys == null ? List.of() : List.copyOf(publicTagKeys);
        myTagIds = myTagIds == null ? List.of() : List.copyOf(myTagIds);
        labelDefinitionIds = labelDefinitionIds == null ? List.of() : List.copyOf(labelDefinitionIds);
    }
}
