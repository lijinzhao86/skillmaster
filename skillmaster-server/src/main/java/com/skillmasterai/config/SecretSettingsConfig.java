package com.skillmasterai.config;

import com.skillmasterai.modules.secret.SecretSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Publishes the credential-vault settings as a module-owned bean, and keeps {@code local} out of a
 * production deployment.
 *
 * <p><strong>The {@code prod} refusal lives here because it needs two things a module cannot
 * see:</strong> the properties record and the active profile. A production deployment that has not
 * switched to KMS would otherwise start successfully and seal every user's credential under a key
 * that lives in a configuration file.
 */
@Configuration(proxyBeanMethods = false)
class SecretSettingsConfig {

    @Bean
    SecretSettings secretSettings(SkillmasterProperties properties, Environment environment) {
        SkillmasterProperties.Secret configured = properties.secret();
        SecretSettings settings = new SecretSettings(
                configured.sealer(),
                configured.kmsKeyId(),
                configured.region(),
                configured.accessKeyId(),
                configured.accessKeySecret(),
                configured.localKey());
        refuseLocalOnProd(settings, environment);
        return settings;
    }

    /**
     * The production gate, as a method so it can be tested without building the whole properties
     * record. A check that only runs at startup would otherwise get its first exercise on the day
     * someone deploys.
     */
    static void refuseLocalOnProd(SecretSettings settings, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))
                && !settings.usesCloudKms()) {
            throw new IllegalStateException(
                    "this deployment is running the `prod` profile but skillmaster.secret.sealer is"
                            + " `local`; set SKILLMASTER_SECRET_SEALER=huawei with a CMK before"
                            + " starting, or values would be sealed by a key in this file");
        }
    }
}
