package com.skillmasterai.modules.activity.internal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The JSON stored in a search event's {@code detail} column (v58).
 *
 * <p>Two copies of the same order: {@code results} is what the page and the CLI display,
 * {@code skill_ids} is the flat array correlation searches. Keeping the flat array is what makes
 * attribution one array lookup instead of parsing nested objects on the invoke path.
 *
 * <p>The names are snake_case because this is a stored document (TD §数据模型) that archive
 * readers open directly; a Java-style rename here would silently change the exported file.
 *
 * @param total    the response's total, or null when the API's search response had none
 * @param returned how many results are stored (at most 50 across all pages)
 * @param pages    how many responses have been merged into this search
 * @param filters  the narrowing that defines "the same query" for page merging
 */
public record SearchDetail(
        List<StoredResult> results,
        @JsonProperty("skill_ids") @JsonAlias("skillIds") List<String> skillIds,
        Long total,
        int returned,
        int pages,
        Map<String, Object> filters) {

    public SearchDetail {
        results = List.copyOf(results);
        skillIds = List.copyOf(skillIds);
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }
}
