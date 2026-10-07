package com.skillmasterai.usecase;

import com.skillmasterai.modules.search.SkillSearchService;
import com.skillmasterai.modules.search.SkillSearchService.SearchPage;
import java.util.Map;

/**
 * One page of a listing, with the namespace slugs its rows need.
 *
 * <p><strong>Two things because they come from two modules.</strong> The page is M7's rows arranged
 * by M8's policy, and each row knows which namespace it is in by id — M4's table is where the slug
 * lives. Since ADR 0034 a page can span namespaces (the caller's own, plus any they hold a grant
 * in), so the slug is no longer a constant the caller already had; this is the composition layer
 * putting the two halves together, which is what §2.5 says the use-case layer is for.
 *
 * @param namespaceSlugs id to slug, for every namespace that appears on this page
 */
public record SkillListing(SearchPage page, Map<String, String> namespaceSlugs) {

    public SkillListing {
        namespaceSlugs = Map.copyOf(namespaceSlugs);
    }
}
