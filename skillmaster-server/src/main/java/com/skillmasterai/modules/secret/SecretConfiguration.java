package com.skillmasterai.modules.secret;

import com.skillmasterai.modules.secret.internal.AliyunKeyWrapper;
import com.skillmasterai.modules.secret.internal.EnvelopeSealer;
import com.skillmasterai.modules.secret.internal.HuaweiKeyWrapper;
import com.skillmasterai.modules.secret.internal.KeyWrapper;
import com.skillmasterai.modules.secret.internal.LocalSealer;
import java.util.function.Function;
import com.skillmasterai.modules.secret.internal.PgSecretService;
import com.skillmasterai.modules.secret.internal.Sealer;
import com.skillmasterai.modules.secret.internal.SecretRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires M16 — the vault, its bindings, its daily use aggregate and its shares.
 *
 * <p>The choice of sealer is here and nowhere else. The settings bean is built by
 * {@code config.SecretSettingsConfig}; this class names neither the properties record nor the
 * active profile, because a module may not read configuration (the same arrangement
 * {@code UsageConfiguration} and {@code BlobSettingsConfig} already use).
 */
@Configuration(proxyBeanMethods = false)
public class SecretConfiguration {

    @Bean
    Sealer secretSealer(SecretSettings settings) {
        return secretSealer(settings, SecretConfiguration::cloudWrapper);
    }

    /**
     * The choice itself, with the cloud client as a parameter.
     *
     * <p>Split out because constructing a cloud client is not free: the Huawei SDK resolves the
     * project id against IAM while it is being built, so a test cannot build one offline. With the
     * wrapper supplied, the mapping from `sealer` to envelope-or-local is testable; the provider
     * line below is exercised by any real deployment.
     */
    static Sealer secretSealer(SecretSettings settings,
            Function<SecretSettings, KeyWrapper> cloud) {
        // The cloud sealers share the envelope: the value never leaves this process in clear, and
        // the KMS only wraps the 32-byte data key (ADR 0172).
        return switch (settings.sealer()) {
            case SecretSettings.HUAWEI, SecretSettings.ALIYUN ->
                    new EnvelopeSealer(cloud.apply(settings));
            case SecretSettings.LOCAL -> {
                byte[] key = settings.localKeyBytes();
                try {
                    yield new LocalSealer(key);
                } finally {
                    // LocalSealer copies the bytes; the copy given to it does not need to stay.
                    java.util.Arrays.fill(key, (byte) 0);
                }
            }
            default -> throw new IllegalStateException(
                    "unsupported sealer: " + settings.sealer());
        };
    }

    /**
     * Which cloud client a sealer value names, with both constructors as parameters.
     *
     * <p>The parameter form exists so the mapping itself is testable: building a real client talks
     * to IAM (the Huawei SDK resolves the project id during {@code build()}), so a test that passed
     * real constructors could not run offline.
     */
    static KeyWrapper cloudWrapper(SecretSettings settings,
            Function<SecretSettings, KeyWrapper> huawei,
            Function<SecretSettings, KeyWrapper> aliyun) {
        return switch (settings.sealer()) {
            case SecretSettings.HUAWEI -> huawei.apply(settings);
            case SecretSettings.ALIYUN -> aliyun.apply(settings);
            default -> throw new IllegalStateException(
                    "not a cloud sealer: " + settings.sealer());
        };
    }

    private static KeyWrapper cloudWrapper(SecretSettings settings) {
        return cloudWrapper(settings, HuaweiKeyWrapper::new, AliyunKeyWrapper::new);
    }

    @Bean
    SecretRepository secretRepository(JdbcClient jdbc) {
        return new SecretRepository(jdbc);
    }

    @Bean
    SecretService secretService(SecretRepository repository, Sealer sealer) {
        return new PgSecretService(repository, sealer);
    }
}
