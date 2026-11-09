package com.skillmasterai.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.modules.secret.SecretSettings;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The production gate: `prod` plus a `local` sealer must not start.
 *
 * <p>Without this test the only exercise the check gets is a real deployment — and its failure
 * mode is a production server that starts fine and seals every user's credential with a key from
 * its own configuration file.
 */
class SecretSettingsConfigTest {

    private static final String LOCAL_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static SecretSettings local() {
        return new SecretSettings("local", "", "", "", "", LOCAL_KEY);
    }

    private static SecretSettings huawei() {
        return new SecretSettings("huawei", "key-1", "cn-east-3", "ak", "sk", "");
    }

    private static MockEnvironment withProfiles(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    void prodRefusesTheLocalSealerByName() {
        assertThatThrownBy(() -> SecretSettingsConfig.refuseLocalOnProd(local(),
                withProfiles("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod")
                .hasMessageContaining("sealer");
    }

    @Test
    void prodAcceptsACloudSealer() {
        assertThatCode(() -> SecretSettingsConfig.refuseLocalOnProd(huawei(),
                withProfiles("prod"))).doesNotThrowAnyException();
    }

    @Test
    void outsideProdTheLocalSealerIsFine() {
        assertThatCode(() -> SecretSettingsConfig.refuseLocalOnProd(local(),
                withProfiles())).doesNotThrowAnyException();
    }
}
