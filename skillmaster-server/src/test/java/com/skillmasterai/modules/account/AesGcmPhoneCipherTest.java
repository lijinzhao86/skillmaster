package com.skillmasterai.modules.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.common.Sha256Hex;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesGcmPhoneCipherTest {

    private static final String HMAC_KEY = "0123456789abcdef0123456789abcdef";
    private static final String AES_KEY = "fedcba9876543210fedcba9876543210";
    private static final String PHONE = "13800138000";

    private final AesGcmPhoneCipher cipher = new AesGcmPhoneCipher(HMAC_KEY, AES_KEY);

    @Test
    void hashesTheSameNumberToTheSameValue() {
        // Determinism is the requirement, not a shortcoming: this value is what login looks up by,
        // and UNIQUE on its column is what makes one phone one account.
        assertThat(cipher.hash(PHONE)).isEqualTo(cipher.hash(PHONE));
        assertThat(cipher.hash(PHONE)).isNotEqualTo(cipher.hash("13800138001"));
    }

    @Test
    void hashesWithTheKeyRatherThanBarelyHashing() {
        // Without this, a change that quietly swapped the HMAC for a plain digest would still pass
        // every other test here — and a bare digest of a phone number is recoverable by hashing the
        // plausible ones.
        String bare = Sha256Hex.of(PHONE.getBytes(StandardCharsets.UTF_8));

        assertThat(cipher.hash(PHONE)).isNotEqualTo(bare);
        assertThat(new AesGcmPhoneCipher(AES_KEY + "x", AES_KEY).hash(PHONE))
                .as("a different key is a different index")
                .isNotEqualTo(cipher.hash(PHONE));
    }

    @Test
    void encryptsTheSameNumberDifferentlyEveryTime() {
        // A random IV per call. Equal ciphertexts would leak that two accounts share a number, and
        // would make the column a second, weaker blind index.
        assertThat(cipher.encrypt(PHONE)).isNotEqualTo(cipher.encrypt(PHONE));
    }

    @Test
    void recoversWhatItEncrypted() {
        assertThat(cipher.decrypt(cipher.encrypt(PHONE))).isEqualTo(PHONE);
    }

    @Test
    void refusesAValueThatWasTamperedWith() {
        // GCM authenticates as well as encrypts, which is why it was chosen over CBC: a value
        // somebody edited fails to open rather than decrypting to a different number.
        byte[] value = Base64.getDecoder().decode(cipher.encrypt(PHONE));
        value[value.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(value)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAValueTooShortToBeItsOwn() {
        // Every length from nothing up to one byte short of IV-plus-tag. The middle of that range is
        // the part a guard written for "is there an IV" misses: such a value reaches the provider,
        // which raises ShortBufferException inside a ProviderException — a RuntimeException that the
        // GeneralSecurityException catch does not translate, so the caller sees a JCE internal error
        // rather than "not a value this cipher produced".
        for (int length = 0; length < 28; length++) {
            byte[] value = new byte[length];
            assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(value)))
                    .as("a %s-byte value", length)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void refusesAKeyShorterThanItSaysItNeeds() {
        assertThatThrownBy(() -> new AesGcmPhoneCipher("short", AES_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phone-hmac-key");

        assertThatThrownBy(() -> new AesGcmPhoneCipher(HMAC_KEY, "short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phone-enc-key");
    }

    @Test
    void refusesAMissingKey() {
        // Startup fails rather than the first registration: an unset key would otherwise be
        // discovered by a user, as a 500.
        assertThatThrownBy(() -> new AesGcmPhoneCipher("", AES_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phone-hmac-key");

        assertThatThrownBy(() -> new AesGcmPhoneCipher(HMAC_KEY, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phone-enc-key");
    }
}
