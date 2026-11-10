package com.skillmasterai.modules.activity;

import java.util.List;
import java.util.Map;

/**
 * One search response (v58).
 *
 * <p><strong>Pages of one query are one search.</strong> A continuation is passed in with
 * {@code continuation = true}; the recorder appends its results to the most recent matching
 * search (same account, same query, same filters, first search within 30 minutes) instead of
 * inserting a row. That is why "搜索次数" counts queries rather than HTTP requests.
 *
 * @param total        the response's total, or null when the API's search response has none
 * @param results      the page in display order; at most 50 are stored across all pages
 * @param continuation true for page 2 and later (`p > 1`, or an API cursor)
 * @param filters      the narrowing that defines "the same query"; empty on the API cursor path
 * @param at           RFC3339 UTC; the merge window is measured against this instant
 */
public record SearchActivity(
        String actorUserId,
        String query,
        Map<String, Object> filters,
        Long total,
        List<SearchHit> results,
        boolean continuation,
        String at) {

    public SearchActivity {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
        results = List.copyOf(results);
    }
}
