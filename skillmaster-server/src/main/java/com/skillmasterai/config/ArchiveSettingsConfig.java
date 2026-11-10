package com.skillmasterai.config;

import com.skillmasterai.modules.activity.ArchiveSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the archive directory as its own bean.
 *
 * <p>Same shape as {@link BlobSettingsConfig}: the properties record lives in {@code config}, and
 * M17 may not import it.
 */
@Configuration(proxyBeanMethods = false)
public class ArchiveSettingsConfig {

    @Bean
    ArchiveSettings archiveSettings(SkillmasterProperties properties) {
        return properties.archive();
    }
}
