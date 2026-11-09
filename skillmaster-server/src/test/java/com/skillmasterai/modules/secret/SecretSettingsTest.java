package com.skillmasterai.modules.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The sealer setting: three values, the fields each requires, and a refusal for everything else.
 *
 * <p>The check runs at startup on purpose — a deployment missing its CMK must fail then, not on
 * the first write, because by then a user believes the value is stored.
 */
class SecretSettingsTest {

    private static final String LOCAL_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    /** Each test names only what it varies. */
    private static SecretSettings settings(String sealer, String keyId, String region, String ak,
            String sk, String localKey) {
        return new SecretSettings(sealer, keyId, region, ak, sk, localKey);
    }

    @Test
    void blankMeansLocalAndNeedsItsKey() {
        assertThat(settings("", "", "", "", "", LOCAL_KEY).sealer())
                .isEqualTo(SecretSettings.LOCAL);
        assertThatThrownBy(() -> settings("", "", "", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local-key");
    }

    @Test
    void bothCloudSealersNeedAKeyAndARegion() {
        for (String sealer : new String[] {SecretSettings.HUAWEI, SecretSettings.ALIYUN}) {
            SecretSettings ok = settings(sealer, "key-1", "cn-east-3", "ak", "sk", "");
            assertThat(ok.sealer()).isEqualTo(sealer);
            assertThat(ok.usesCloudKms()).isTrue();

            assertThatThrownBy(() -> settings(sealer, "", "cn-east-3", "ak", "sk", ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("kms-key-id");
            assertThatThrownBy(() -> settings(sealer, "key-1", "", "ak", "sk", ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("region");
        }
    }

    @Test
    void huaweiNeedsTheAccessKeyPairButAliyunMayFallBackToTheHost() {
        // Huawei's default chain probes the Huawei metadata service; this host is an Aliyun ECS,
        // so a blank pair would hang through the connect timeout and fail without naming a key.
        assertThatThrownBy(() -> settings(SecretSettings.HUAWEI, "key-1", "cn-east-3", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("access-key-id");
        assertThat(settings(SecretSettings.ALIYUN, "key-1", "cn-east-3", "", "", "").sealer())
                .isEqualTo(SecretSettings.ALIYUN);
    }

    @Test
    void halfAnAccessKeyIsRefused() {
        for (String sealer : new String[] {SecretSettings.HUAWEI, SecretSettings.ALIYUN}) {
            assertThatThrownBy(() -> settings(sealer, "key-1", "cn-east-3", "ak-only", "", ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("access-key-id");
        }
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        SecretSettings trimmed = settings(" HUAWEI ", " key-1 ", " cn-east-3 ", " ak ", " sk ",
                "");
        assertThat(trimmed.sealer()).isEqualTo(SecretSettings.HUAWEI);
        assertThat(trimmed.kmsKeyId()).isEqualTo("key-1");
        assertThat(trimmed.region()).isEqualTo("cn-east-3");
        assertThat(trimmed.accessKeyId()).isEqualTo("ak");
    }

    @Test
    void aLocalKeyThatIsNotBase64IsRefused() {
        assertThatThrownBy(() -> settings("", "", "", "", "", "not base64!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void anUnknownSealerIsRefusedByName() {
        assertThatThrownBy(() -> settings("kms", "key-1", "cn-east-3", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'kms'");
    }

    @Test
    void aLocalKeyThatIsNot32BytesIsRefused() {
        String sixteen = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> settings("", "", "", "", "", sixteen))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }
}
