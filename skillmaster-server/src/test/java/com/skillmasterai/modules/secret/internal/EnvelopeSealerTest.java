package com.skillmasterai.modules.secret.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The envelope: a value of any size round-trips, the KMS is only ever handed the data key, and a
 * damaged blob fails instead of producing bytes.
 *
 * <p>The wrapper here reverses the bytes it is given. That is enough to prove the seal does go
 * through it — the value's GCM is what must catch tampering, not the wrapper.
 */
class EnvelopeSealerTest {

    /** Records what it was asked to wrap, so a test can assert the value never reached the "KMS". */
    private static final class ReversingWrapper implements KeyWrapper {
        final List<byte[]> wrapped = new ArrayList<>();

        @Override
        public byte[] wrap(byte[] dataKey) {
            wrapped.add(dataKey.clone());
            return reverse(dataKey);
        }

        @Override
        public byte[] unwrap(byte[] reversed) {
            return reverse(reversed);
        }

        private static byte[] reverse(byte[] in) {
            byte[] out = new byte[in.length];
            for (int i = 0; i < in.length; i++) {
                out[i] = in[in.length - 1 - i];
            }
            return out;
        }
    }

    @Test
    void thirtyTwoKilobytesRoundTrips() {
        ReversingWrapper wrapper = new ReversingWrapper();
        EnvelopeSealer sealer = new EnvelopeSealer(wrapper);
        // The value limit the product promises; a KMS that took the plaintext directly would
        // refuse this at 3–6 KB, which is why the envelope exists.
        byte[] plaintext = new byte[32 * 1024];
        new Random(42).nextBytes(plaintext);

        byte[] blob = sealer.encrypt(plaintext);

        assertThat(sealer.decrypt(blob)).isEqualTo(plaintext);
        // One wrap, of exactly the 32-byte data key — never of the value.
        assertThat(wrapper.wrapped).hasSize(1);
        assertThat(wrapper.wrapped.get(0)).hasSize(32);
    }

    @Test
    void theSameValueSealsDifferentlyEveryTime() {
        EnvelopeSealer sealer = new EnvelopeSealer(new ReversingWrapper());
        byte[] plaintext = "a value".getBytes(StandardCharsets.UTF_8);

        assertThat(sealer.encrypt(plaintext)).isNotEqualTo(sealer.encrypt(plaintext));
    }

    @Test
    void aTamperedBlobFailsRatherThanReturningGarbage() {
        EnvelopeSealer sealer = new EnvelopeSealer(new ReversingWrapper());
        byte[] blob = sealer.encrypt("value".getBytes(StandardCharsets.UTF_8));
        blob[blob.length - 1] ^= 0x01;

        assertThatThrownBy(() -> sealer.decrypt(blob))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTamperedDataKeyFailsToo() {
        EnvelopeSealer sealer = new EnvelopeSealer(new ReversingWrapper());
        byte[] blob = sealer.encrypt("value".getBytes(StandardCharsets.UTF_8));
        // Byte 3 is the first byte of the wrapped data key.
        blob[3] ^= 0x01;

        assertThatThrownBy(() -> sealer.decrypt(blob))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnknownFormatOrShortBlobIsRefused() {
        EnvelopeSealer sealer = new EnvelopeSealer(new ReversingWrapper());
        byte[] blob = sealer.encrypt("value".getBytes(StandardCharsets.UTF_8));
        blob[0] = 9;

        assertThatThrownBy(() -> sealer.decrypt(blob))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sealer.decrypt(new byte[4]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyValueRoundTrips() {
        EnvelopeSealer sealer = new EnvelopeSealer(new ReversingWrapper());

        assertThat(sealer.decrypt(sealer.encrypt(new byte[0]))).isEmpty();
    }

    @Test
    void everyTruncationOfAValidBlobIsRefused() {
        EnvelopeSealer sealer = new EnvelopeSealer(new ReversingWrapper());
        byte[] blob = sealer.encrypt("value".getBytes(StandardCharsets.UTF_8));

        for (int length = 0; length < blob.length; length++) {
            byte[] cut = Arrays.copyOf(blob, length);
            assertThatThrownBy(() -> sealer.decrypt(cut))
                    .as("truncated to %d of %d bytes", length, blob.length)
                    .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
        }
    }

    @Test
    void aZeroOrOversizedLengthFieldIsRefused() {
        EnvelopeSealer sealer = new EnvelopeSealer(new ReversingWrapper());

        byte[] zero = sealer.encrypt("value".getBytes(StandardCharsets.UTF_8));
        zero[1] = 0;
        zero[2] = 0;
        assertThatThrownBy(() -> sealer.decrypt(zero))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] oversized = sealer.encrypt("value".getBytes(StandardCharsets.UTF_8));
        oversized[1] = (byte) 0xFF;
        oversized[2] = (byte) 0xFF;
        assertThatThrownBy(() -> sealer.decrypt(oversized))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
