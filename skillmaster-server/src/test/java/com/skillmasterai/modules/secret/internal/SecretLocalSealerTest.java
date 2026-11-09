package com.skillmasterai.modules.secret.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** AES-GCM round trip, fresh IVs, and a tampered blob that must not decrypt. */
class SecretLocalSealerTest {

    private static byte[] key() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        return key;
    }

    @Test
    void theSamePlaintextRoundTripsAndIsNeverStoredInTheClear() {
        LocalSealer sealer = new LocalSealer(key());
        byte[] plaintext = "test-cookie-value".getBytes(StandardCharsets.UTF_8);

        byte[] sealed = sealer.encrypt(plaintext);

        assertThat(sealed).isNotEqualTo(plaintext);
        assertThat(sealer.decrypt(sealed)).isEqualTo(plaintext);
    }

    @Test
    void theSamePlaintextSealsDifferentlyEveryTime() {
        LocalSealer sealer = new LocalSealer(key());
        byte[] plaintext = "a value".getBytes(StandardCharsets.UTF_8);

        // A reused IV would make two identical values indistinguishable in the table and weaken
        // GCM; the random IV is why this must not be equal.
        assertThat(sealer.encrypt(plaintext)).isNotEqualTo(sealer.encrypt(plaintext));
    }

    @Test
    void aTamperedBlobFailsRatherThanReturningGarbage() {
        LocalSealer sealer = new LocalSealer(key());
        byte[] sealed = sealer.encrypt("value".getBytes(StandardCharsets.UTF_8));
        sealed[sealed.length - 1] ^= 0x01;

        assertThatThrownBy(() -> sealer.decrypt(sealed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aKeyThatIsNot32BytesIsRefused() {
        assertThatThrownBy(() -> new LocalSealer(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
