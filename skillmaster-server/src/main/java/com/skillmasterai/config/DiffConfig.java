package com.skillmasterai.config;

import com.skillmasterai.modules.distribution.DiffLimits;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hands M9 the diff limits it was configured with.
 *
 * <p>The same detour {@link RankingConfig} takes, for the same reason: M9's own wiring class cannot
 * read {@link SkillmasterProperties}, because the architecture rule forbids a module from depending
 * on {@code config}. The bean is one line because the property is already typed as M9's own record —
 * there is no second copy of the three numbers to keep in step.
 */
@Configuration(proxyBeanMethods = false)
class DiffConfig {

    @Bean
    DiffLimits diffLimits(SkillmasterProperties properties) {
        return properties.diff();
    }
}
