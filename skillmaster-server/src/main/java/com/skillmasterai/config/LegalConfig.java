package com.skillmasterai.config;

import com.skillmasterai.usecase.AccountRetention;
import com.skillmasterai.usecase.LegalTextVersions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hands the registration and purge paths their two values (v56).
 *
 * <p>The same detour {@link WelcomeConfig} takes: no layer below this one depends on
 * {@link SkillmasterProperties}. The property record owns the values; these two beans are the typed
 * shapes the use cases take.
 */
@Configuration(proxyBeanMethods = false)
class LegalConfig {

    @Bean
    LegalTextVersions legalTextVersions(SkillmasterProperties properties) {
        return new LegalTextVersions(
                properties.account().termsVersion(), properties.account().privacyVersion());
    }

    @Bean
    AccountRetention accountRetention(SkillmasterProperties properties) {
        return new AccountRetention(properties.account().deletionRetention());
    }
}
