package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.search.InvalidSearchRequestException;
import com.skillmasterai.modules.search.SearchRequest;
import com.skillmasterai.modules.skill.LabelService;
import com.skillmasterai.modules.skill.SkillCategoryService;
import com.skillmasterai.modules.skill.SkillTagService;
import com.skillmasterai.modules.skill.TagNames;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Searching the public plane, and only the public plane (v14, ADR 0082).
 *
 * <p><strong>At least one of a keyword, a category or a public tag.</strong> The public plane is
 * never listed whole — it will be too large to load — so an empty request is a 400 rather than a
 * page of everything. That rule is the same on both transports; only the addressing differs: the
 * CLI sends names, the console sends category keys.
 *
 * <p>The policy is M8's and the SQL is M12's, exactly as before. This use case resolves the names
 * and enforces the "not empty" rule; it does not rank anything.
 */
@Component
public class SearchPublicSkillsUseCase {

    /** The console's discovery results show six at a time (TD Q4). */
    public static final int WEB_PAGE_SIZE = 6;

    private final SkillCategoryService categories;
    private final SkillTagService tags;
    private final LabelService labels;
    private final SearchSkillsUseCase search;

    public SearchPublicSkillsUseCase(SkillCategoryService categories, SkillTagService tags,
            LabelService labels, SearchSkillsUseCase search) {
        this.categories = categories;
        this.tags = tags;
        this.labels = labels;
        this.search = search;
    }

    /** The API plane and the CLI: filters are the names a person typed. */
    @Transactional(readOnly = true)
    public SkillListing api(String q, List<String> categoryNames, List<String> publicTagNames,
            List<String> labelKeys, Integer limit, String cursor, List<String> excludedIds,
            AuthenticatedSubject subject) {
        List<String> names = categoryNames == null ? List.of() : categoryNames;
        List<String> words = publicTagNames == null ? List.of() : publicTagNames;
        List<String> askedLabels = labelKeys == null ? List.of() : labelKeys;
        requireSomething(q, names, words, askedLabels);
        List<String> labelIds = resolveLabels(askedLabels);

        List<String> categoryKeys = new ArrayList<>();
        for (String name : names) {
            String key = categoryKey(name);
            if (key == null) {
                // An unknown name is not an error the product names; it is a filter that selects
                // nothing, and the CLI prints the empty result plus a pointer to the vocabulary.
                return empty();
            }
            categoryKeys.add(key);
        }
        List<String> publicTagKeys = new ArrayList<>();
        for (String word : words) {
            String key = TagNames.key(TagNames.display(word));
            if (key.isEmpty()) {
                return empty();
            }
            publicTagKeys.add(key);
        }

        SearchRequest request = new SearchRequest(q, List.of(), categoryKeys, publicTagKeys,
                SearchRequest.SortOrder.RELEVANCE,
                limit == null ? SearchRequest.DEFAULT_LIMIT : limit, cursor, null, excludedIds,
                labelIds);
        return search.searchPublic(request, subject);
    }

    /** The console: one category filter, addressed by key, a page number and the total it needs. */
    @Transactional(readOnly = true)
    public WebResult web(String q, List<String> categoryKeys, List<String> labelKeys,
            Integer page, AuthenticatedSubject subject) {
        List<String> keys = categoryKeys == null ? List.of() : categoryKeys;
        List<String> askedLabels = labelKeys == null ? List.of() : labelKeys;
        requireSomething(q, keys, List.of(), askedLabels);
        List<String> labelIds = resolveLabels(askedLabels);
        int wanted = page == null || page < 1 ? 1 : page;
        // Wide arithmetic: an int overflow would reach PostgreSQL as a negative OFFSET.
        long wide = ((long) wanted - 1) * WEB_PAGE_SIZE;
        if (wide > Integer.MAX_VALUE) {
            throw new InvalidSearchRequestException("page is too large");
        }
        SearchRequest request = new SearchRequest(q, List.of(), keys, List.of(),
                SearchRequest.SortOrder.RELEVANCE, WEB_PAGE_SIZE, null, (int) wide, List.of(),
                labelIds);
        return new WebResult(search.searchPublic(request, subject).cards(), wanted,
                WEB_PAGE_SIZE, search.countPublic(request, subject));
    }

    /**
     * @param total the whole filtered result, not the page — a page-number pager cannot draw its
     *              pages without it
     */
    public record WebResult(List<com.skillmasterai.usecase.model.SkillCardView> cards, int page,
            int pageSize, long total) {
        public WebResult {
            cards = List.copyOf(cards);
        }
    }

    private String categoryKey(String name) {
        String key = TagNames.key(TagNames.display(name));
        for (SkillCategoryService.CategoryRow row : categories.all()) {
            // Retired words are not filterable (PRD #7): the name resolves to nothing, so the
            // search answers "no match" instead of quietly filtering by a word nobody can pick.
            //
            // **Two spellings name the same shelf**, and both arrive here: the CLI and the API take
            // the display name, the console's picker sends the key. Accepting only the name made the
            // same request answer differently depending on which search ran — the semantic path
            // accepts both, so this one has to as well.
            if (!"retired".equals(row.state())
                    && (TagNames.key(TagNames.display(row.name())).equals(key)
                            || row.key().equals(key))) {
                return row.key();
            }
        }
        return null;
    }

    private static void requireSomething(String q, List<String> categories, List<String> tags,
            List<String> labels) {
        if ((q == null || q.isBlank()) && categories.isEmpty() && tags.isEmpty()
                && labels.isEmpty()) {
            throw new InvalidSearchRequestException(
                    "a public search needs a query, a category, a public tag or a label");
        }
    }

    /**
     * Resolve the wire keys to definition ids. An unknown key is a 400 naming it: the caller
     * chose a capsule from the vocabulary, so a misspelling is theirs to fix, not an empty result
     * that looks like "nothing matched".
     */
    private List<String> resolveLabels(List<String> keys) {
        List<String> ids = new ArrayList<>();
        for (String key : keys) {
            ids.add(labels.definitionIdOf(key)
                    .orElseThrow(() -> new InvalidSearchRequestException(
                            "unknown label '" + key + "'")));
        }
        return ids;
    }

    private static SkillListing empty() {
        return new SkillListing(
                new com.skillmasterai.modules.search.SkillSearchService.SearchPage(
                        List.of(), null),
                java.util.Map.of(), List.of());
    }
}
