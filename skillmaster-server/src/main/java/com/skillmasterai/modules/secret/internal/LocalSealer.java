package com.skillmasterai.modules.secret.internal;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with a random 96-bit IV, for local development and tests only.
 *
 * <p>The IV is prepended to the ciphertext, so the stored blob is self-contained; GCM's tag is
 * appended by the provider. A 96-bit IV is the size GCM is defined for, and a fresh random one per
 * encryption is what keeps the same value from sealing to the same blob twice.
 *
 * <p>The production refusal is not here: a module may not read the profile. {@code SecretSettings}
 * and {@code config.SecretSettingsConfig} are where {@code local} is kept out of a production
 * deployment.
 */
public final class LocalSealer implements Sealer {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public LocalSealer(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("a local sealer key must be 32 bytes (AES-256)");
        }
        this.key = new SecretKeySpec(Arrays.copyOf(keyBytes, keyBytes.length), "AES");
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext);
            byte[] blob = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, blob, 0, IV_BYTES);
            System.arraycopy(sealed, 0, blob, IV_BYTES, sealed.length);
            return blob;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("local sealing failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length <= IV_BYTES) {
            throw new IllegalArgumentException("sealed blob is too short");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_BYTES));
            return cipher.doFinal(ciphertext, IV_BYTES, ciphertext.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("local unsealing failed", e);
        }
    }
}
