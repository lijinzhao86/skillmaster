package com.skillmasterai.modules.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The 32 KiB environment-variable ceiling, at the boundary. */
class SecretValueLimitTest {

    @Test
    void exactly32KiBIsAccepted() {
        byte[] atLimit = new byte[CredentialValue.MAX_BYTES];

        assertThat(new CredentialValue(atLimit).byteLength())
                .isEqualTo(CredentialValue.MAX_BYTES);
    }

    @Test
    void oneByteMoreIsRefusedWithBothCounts() {
        byte[] tooLarge = new byte[CredentialValue.MAX_BYTES + 1];

        assertThatThrownBy(() -> new CredentialValue(tooLarge))
                .isInstanceOfSatisfying(SecretValueTooLargeException.class, refused -> {
                    assertThat(refused.code()).isEqualTo(ErrorCode.CREDENTIAL_VALUE_TOO_LARGE);
                    assertThat(refused.getMessage()).contains("32768");
                });
    }

    @Test
    void theByteArrayIsCopiedInAndOut() {
        byte[] source = "value".getBytes(StandardCharsets.UTF_8);
        CredentialValue credential = new CredentialValue(source);
        source[0] = 'X';

        assertThat(credential.bytes()).isEqualTo("value".getBytes(StandardCharsets.UTF_8));
        assertThat(credential.bytes()).isNotSameAs(credential.bytes());
    }
}
