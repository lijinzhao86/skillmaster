package com.skillmasterai.modules.activity.internal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One search result as it is stored in {@code detail.results} (v58).
 *
 * <p>The rank is the position in the stored list, 1-based. It is deliberately not the response's
 * offset: a merged second page continues this list, and correlation reads the same list, so both
 * sides mean the same thing by "第 K 个结果".
 *
 * <p>Field names are snake_case because {@code detail} is a documented storage shape (TD
 * §数据模型), not a Java object: the same file is read by DuckDB after archiving.
 */
public record StoredResult(
        @JsonProperty("skill_id") @JsonAlias("skillId") String skillId,
        String address,
        int rank) {
}
