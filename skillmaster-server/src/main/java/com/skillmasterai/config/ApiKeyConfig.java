package com.skillmasterai.config;

import com.skillmasterai.modules.token.ApiKeyPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hands M2's expiry policy to whoever issues keys.
 *
 * <p>The same one-line detour {@link DiffConfig} takes, for the same reason: the use case that
 * issues a key may not read {@link SkillmasterProperties} either — the architecture rule keeps
 * configuration out of both modules and use cases — so what the deployment chose arrives as a bean.
 * One line, because the property is already typed as M2's own record: there is no second copy of the
 * number to keep in step.
 */
@Configuration(proxyBeanMethods = false)
class ApiKeyConfig {

    @Bean
    ApiKeyPolicy apiKeyPolicy(SkillmasterProperties properties) {
        return properties.apiKeys();
    }
}
