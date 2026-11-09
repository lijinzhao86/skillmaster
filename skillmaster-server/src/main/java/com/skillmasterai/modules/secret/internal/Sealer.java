package com.skillmasterai.modules.secret.internal;

/**
 * The one operation the rest of M16 asks of a key: turn plaintext into an opaque blob, and back.
 *
 * <p>Returned and accepted as bytes rather than as text because the sealed form is binary — for
 * the cloud sealers, the envelope that carries the KMS-wrapped data key; the repository is what
 * base64s it into the TEXT column, keeping the wire/storage encoding out of this seam.
 */
public interface Sealer {

    /** @return the opaque ciphertext; the same plaintext seals differently every time */
    byte[] encrypt(byte[] plaintext);

    /** @return the plaintext; throws when the blob was not produced by this sealer's key */
    byte[] decrypt(byte[] ciphertext);
}
