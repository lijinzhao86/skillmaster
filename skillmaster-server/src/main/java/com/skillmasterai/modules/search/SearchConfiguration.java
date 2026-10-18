package com.skillmasterai.modules.search;

import com.skillmasterai.modules.skill.SkillCatalogService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires M8.
 *
 * <p>No {@code JdbcClient} here, and that is the point: this module holds no SQL. The query is M7's
 * (see {@link SkillSearchService}), so what M8 needs is M7's catalog seam, not a database.
 *
 * <p>{@link RelevanceWeights} arrives as a bean rather than being read from configuration here,
 * because a module's wiring may not depend on {@code config}. See {@code RankingConfig}, which
 * builds it. The record validates itself, so a nonsensical configuration fails at startup rather
 * than quietly reordering everyone's results.
 */
@Configuration(proxyBeanMethods = false)
public class SearchConfiguration {

    @Bean
    SkillSearchService skillSearchService(SkillCatalogService catalog, RelevanceWeights weights) {
        return new SkillSearchService(catalog, weights);
    }
}
