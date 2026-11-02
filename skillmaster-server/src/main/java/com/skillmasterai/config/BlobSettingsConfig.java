package com.skillmasterai.config;

import com.skillmasterai.modules.blob.BlobSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the blob settings as their own bean.
 *
 * <p>The properties record lives in {@code config}; M6 must not import it (the module rules allow
 * a module to depend on its own published types, not on the application's configuration package).
 * This one-line adapter is the same shape {@link DiffConfig} uses for M9's limits.
 */
@Configuration(proxyBeanMethods = false)
public class BlobSettingsConfig {

    @Bean
    BlobSettings blobSettings(SkillmasterProperties properties) {
        return properties.blobs();
    }
}
