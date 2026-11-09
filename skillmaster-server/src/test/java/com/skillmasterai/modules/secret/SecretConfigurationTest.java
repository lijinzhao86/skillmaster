package com.skillmasterai.modules.secret;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.secret.internal.EnvelopeSealer;
import com.skillmasterai.modules.secret.internal.KeyWrapper;
import com.skillmasterai.modules.secret.internal.LocalSealer;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Which sealing path each `sealer` value picks.
 *
 * <p>The integration suite runs with `local`, so a wrong branch here — a cloud value wired to the
 * local key, or the reverse — would stay invisible until production. The cloud wrapper is a
 * parameter because building the real one resolves a project id against IAM, which a unit test
 * cannot do offline.
 */
class SecretConfigurationTest {

    private static final String LOCAL_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static final KeyWrapper FAKE = new KeyWrapper() {
        @Override
        public byte[] wrap(byte[] dataKey) {
            return dataKey.clone();
        }

        @Override
        public byte[] unwrap(byte[] wrapped) {
            return wrapped.clone();
        }
    };

    @Test
    void cloudSealersUseTheEnvelopeAndLocalUsesTheLocalKey() {
        for (String sealer : new String[] {SecretSettings.HUAWEI, SecretSettings.ALIYUN}) {
            assertThat(SecretConfiguration.secretSealer(
                    new SecretSettings(sealer, "key-1", "cn-east-3", "ak", "sk", ""),
                    settings -> FAKE))
                    .as(sealer)
                    .isInstanceOf(EnvelopeSealer.class);
        }
        assertThat(SecretConfiguration.secretSealer(
                new SecretSettings("local", "", "", "", "", LOCAL_KEY), settings -> FAKE))
                .isInstanceOf(LocalSealer.class);
    }

    @Test
    void eachCloudSealerPicksItsOwnClient() {
        KeyWrapper huawei = new KeyWrapper() {
            @Override
            public byte[] wrap(byte[] dataKey) {
                return dataKey.clone();
            }

            @Override
            public byte[] unwrap(byte[] wrapped) {
                return wrapped.clone();
            }
        };
        KeyWrapper aliyun = new KeyWrapper() {
            @Override
            public byte[] wrap(byte[] dataKey) {
                return dataKey.clone();
            }

            @Override
            public byte[] unwrap(byte[] wrapped) {
                return wrapped.clone();
            }
        };

        assertThat(SecretConfiguration.cloudWrapper(
                new SecretSettings("huawei", "k", "cn-east-3", "ak", "sk", ""),
                settings -> huawei, settings -> aliyun)).isSameAs(huawei);
        assertThat(SecretConfiguration.cloudWrapper(
                new SecretSettings("aliyun", "k", "cn-east-3", "ak", "sk", ""),
                settings -> huawei, settings -> aliyun)).isSameAs(aliyun);
    }
}
