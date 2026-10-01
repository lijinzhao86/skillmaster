package com.skillmasterai.config;

import com.skillmasterai.modules.account.PasswordHasher;
import com.skillmasterai.modules.account.PhoneCipher;
import com.skillmasterai.modules.account.AesGcmPhoneCipher;
import com.skillmasterai.modules.account.BCryptPasswordHasher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds M1's two cryptographic primitives from configuration.
 *
 * <p>Here rather than in the module for the reason {@link RankingConfig} exists: a module may not
 * depend on {@code config}, so "what this deployment configures" is turned into a bean in this
 * package and handed over. M1 receives a {@link PhoneCipher} and a {@link PasswordHasher} and has
 * no way to reach the keys behind them.
 *
 * <p>Both constructors validate, so a key that is missing or too short stops the application at
 * startup rather than at somebody's first registration.
 */
@Configuration(proxyBeanMethods = false)
class AccountSecurityConfig {

    @Bean
    PhoneCipher phoneCipher(SkillmasterProperties properties) {
        return new AesGcmPhoneCipher(properties.account().phoneHmacKey(),
                properties.account().phoneEncKey());
    }

    @Bean
    PasswordHasher passwordHasher(SkillmasterProperties properties) {
        return new BCryptPasswordHasher(properties.account().bcryptStrength());
    }
}
