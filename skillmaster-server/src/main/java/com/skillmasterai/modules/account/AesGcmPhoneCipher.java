package com.skillmasterai.modules.account;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 for the blind index, AES-256-GCM for the ciphertext.
 *
 * <p>Two algorithms because the two columns answer different questions (see {@link PhoneCipher}),
 * and neither one alone would do: a bare digest of a phone number is recoverable by hashing the
 * plausible ones, which is a small enough space to enumerate on a laptop, and a ciphertext cannot
 * carry a unique index because encrypting the same number twice gives different bytes.
 *
 * <p>GCM rather than CBC because it authenticates as well as encrypts: a value that has been
 * tampered with fails to open rather than decrypting to something else. A random 96-bit IV per call
 * is what keeps two encryptions of one number from being equal — the IV is not secret, and it is
 * stored alongside the ciphertext rather than derived.
 */
public final class AesGcmPhoneCipher implements PhoneCipher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int AES_KEY_BYTES = 32;
    private static final int MIN_KEY_BYTES = 32;

    /**
     * Domain separation, so that this key is used for one thing.
     *
     * <p>Not to keep this hash apart from the throttle table's: those addresses are plain SHA-256,
     * unkeyed, and {@code scope} is part of that table's primary key, so the two could not collide
     * even if they were the same shape. It is here because a key that hashes one kind of identifier
     * is a key somebody will later reuse for another, and a prefix is what makes that reuse visible
     * rather than silent.
     */
    private static final byte[] HASH_DOMAIN = "phone:".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec hmacKey;
    private final SecretKeySpec aesKey;
    private final SecureRandom random = new SecureRandom();

    /**
     * <p>Both keys are used as their UTF-8 bytes, with no derivation, so what is configured has to
     * be the key: 32 random bytes, written as hex or base64. A passphrase of the same length is
     * accepted and is a much smaller space — and the thing it protects is a table of phone numbers,
     * whose own space is small enough to enumerate.
     *
     * @param hmacKey at least 32 bytes, as text
     * @param aesKey exactly 32 bytes, as text — AES-256
     * @throws IllegalStateException when either is missing or the wrong size. Checked in the
     *         constructor so a bad deployment stops at startup instead of at the first registration.
     */
    public AesGcmPhoneCipher(String hmacKey, String aesKey) {
        this.hmacKey = new SecretKeySpec(
                keyBytes(hmacKey, "skillmaster.account.phone-hmac-key"), HMAC_ALGORITHM);

        byte[] aes = keyBytes(aesKey, "skillmaster.account.phone-enc-key");
        if (aes.length != AES_KEY_BYTES) {
            throw new IllegalStateException(
                    "skillmaster.account.phone-enc-key must be exactly " + AES_KEY_BYTES
                            + " bytes (AES-256), not " + aes.length + ".");
        }
        this.aesKey = new SecretKeySpec(aes, "AES");
    }

    @Override
    public String hash(String phone) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            mac.update(HASH_DOMAIN);
            return HexFormat.of().formatHex(mac.doFinal(phone.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable in this JVM", e);
        }
    }

    @Override
    public String encrypt(String phone) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte[] sealed = run(Cipher.ENCRYPT_MODE, iv, phone.getBytes(StandardCharsets.UTF_8));

        // The IV travels with the ciphertext rather than being stored in a third column: it is not
        // secret, and keeping the two together is what makes the value self-contained.
        byte[] value = new byte[iv.length + sealed.length];
        System.arraycopy(iv, 0, value, 0, iv.length);
        System.arraycopy(sealed, 0, value, iv.length, sealed.length);
        return Base64.getEncoder().encodeToString(value);
    }

    @Override
    public String decrypt(String encrypted) {
        byte[] value = Base64.getDecoder().decode(encrypted);
        if (value.length < IV_BYTES + TAG_BITS / 8) {
            // Too short to hold an IV *and* a tag. The tag's bytes are the half that is easy to
            // forget: a 13-byte value passes an IV-only check and then fails inside the provider,
            // which raises ShortBufferException wrapped in a ProviderException — a RuntimeException
            // that the catch below, being about GeneralSecurityException, does not turn into
            // anything a caller can read. Said plainly here, because the alternative reads like a
            // bug in this class.
            throw new IllegalArgumentException("not a value this cipher produced");
        }
        byte[] plain = run(Cipher.DECRYPT_MODE, Arrays.copyOf(value, IV_BYTES),
                Arrays.copyOfRange(value, IV_BYTES, value.length));
        return new String(plain, StandardCharsets.UTF_8);
    }

    private byte[] run(int mode, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            // On the decrypt path this is normally AEADBadTagException: the value did not
            // authenticate, so it is not one this key produced. Wrapped rather than declared,
            // because every caller is already inside a transaction it cannot usefully retry.
            throw new IllegalStateException("AES-GCM failed", e);
        }
    }

    private static byte[] keyBytes(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    property + " is not set. M1 stores a phone number as a keyed hash plus a "
                            + "ciphertext and can do neither without a key.");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    property + " is " + bytes.length + " bytes; at least " + MIN_KEY_BYTES
                            + " are required. This key is the only thing between a leaked table and "
                            + "a list of phone numbers, and the space of plausible numbers is small "
                            + "enough to enumerate.");
        }
        return bytes;
    }
}
