package com.skillmasterai.config;

import com.skillmasterai.usecase.WelcomeSkills;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hands the registration path the addresses {@code skillmaster.welcome.skills} names (ADR 0144).
 *
 * <p>The same detour {@link RankingConfig} takes: no layer depends on {@link SkillmasterProperties},
 * so the config layer turns the configured strings into a typed value and passes it down. The
 * properties record owns the shape check; splitting {@code namespace/name} is the one step after it,
 * and keeping both here means the use case never sees a string it would have to parse.
 */
@Configuration(proxyBeanMethods = false)
class WelcomeConfig {

    @Bean
    WelcomeSkills welcomeSkills(SkillmasterProperties properties) {
        List<WelcomeSkills.Skill> addresses = properties.welcome().skills().stream()
                .map(WelcomeConfig::split)
                .toList();
        return new WelcomeSkills(addresses);
    }

    private static WelcomeSkills.Skill split(String address) {
        // Shape already checked by SkillmasterProperties.Welcome: exactly one slash, both parts
        // non-blank, no `@`.
        int slash = address.indexOf('/');
        return new WelcomeSkills.Skill(address.substring(0, slash), address.substring(slash + 1));
    }
}
