package com.skillmasterai.modules.secret.internal;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Envelope encryption: the value is sealed locally with a fresh data key, and only that data key is
 * wrapped by the cloud KMS.
 *
 * <p>The blob is self-describing and versioned:
 *
 * <pre>
 *   byte 0         format version (1)
 *   bytes 1..2     big-endian length of the wrapped data key
 *   ...            wrapped data key   (what the KMS returned)
 *   ...            12-byte GCM IV
 *   ...            AES-256-GCM ciphertext, tag appended by the provider
 * </pre>
 *
 * <p>A version byte rather than a migration: the short value is the format, and a future change to
 * the layout can read old blobs and write new ones. {@code Plaintext} never reaches the KMS; a
 * successful unwrap is a 32-byte key, and a wrong one makes GCM fail rather than return bytes.
 */
public final class EnvelopeSealer implements Sealer {

    private static final byte FORMAT = 1;
    private static final int DATA_KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyWrapper wrapper;

    public EnvelopeSealer(KeyWrapper wrapper) {
        this.wrapper = wrapper;
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        byte[] dataKey = new byte[DATA_KEY_BYTES];
        RANDOM.nextBytes(dataKey);
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext);
            byte[] wrapped = wrapper.wrap(dataKey);
            if (wrapped.length > 0xFFFF) {
                throw new IllegalStateException("KMS returned an oversized wrapped key");
            }
            byte[] blob = new byte[3 + wrapped.length + IV_BYTES + sealed.length];
            blob[0] = FORMAT;
            blob[1] = (byte) (wrapped.length >>> 8);
            blob[2] = (byte) wrapped.length;
            System.arraycopy(wrapped, 0, blob, 3, wrapped.length);
            System.arraycopy(iv, 0, blob, 3 + wrapped.length, IV_BYTES);
            System.arraycopy(sealed, 0, blob, 3 + wrapped.length + IV_BYTES, sealed.length);
            return blob;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("envelope sealing failed", e);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public byte[] decrypt(byte[] blob) {
        if (blob == null || blob.length < 3 + 1 + IV_BYTES + TAG_BITS / 8) {
            throw new IllegalArgumentException("sealed blob is too short");
        }
        if (blob[0] != FORMAT) {
            throw new IllegalArgumentException("unknown sealed format: " + (blob[0] & 0xFF));
        }
        int wrappedLength = ((blob[1] & 0xFF) << 8) | (blob[2] & 0xFF);
        int ivAt = 3 + wrappedLength;
        int sealedAt = ivAt + IV_BYTES;
        if (wrappedLength == 0 || sealedAt >= blob.length) {
            throw new IllegalArgumentException("sealed blob is malformed");
        }
        byte[] dataKey = wrapper.unwrap(Arrays.copyOfRange(blob, 3, ivAt));
        if (dataKey == null || dataKey.length != DATA_KEY_BYTES) {
            // A short key would still be accepted by SecretKeySpec (AES-128/192) and only fail at
            // the GCM tag; naming it here says the wrap, not the value, is what got damaged.
            // Not `CredentialVaultUnavailableException`: a KMS that answered with a wrong-shaped
            // key, or a stored blob whose wrapped key was damaged, is bad data, not an outage the
            // caller should retry. 500 says "something is wrong with this value or this key".
            if (dataKey != null) {
                Arrays.fill(dataKey, (byte) 0);
            }
            throw new IllegalStateException("the wrapped data key did not unwrap to 32 bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, blob, ivAt, IV_BYTES));
            return cipher.doFinal(blob, sealedAt, blob.length - sealedAt);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("envelope unsealing failed", e);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }
}
