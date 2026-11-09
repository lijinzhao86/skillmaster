package com.skillmasterai.modules.secret.internal;

/**
 * Wraps and unwraps the 32-byte data key that {@link EnvelopeSealer} encrypts values with.
 *
 * <p>**Why the value is not handed to the KMS directly.** Both providers cap one call at a few
 * kilobytes of plaintext (Huawei's {@code EncryptData} takes 4096 Base64 characters, Aliyun's
 * {@code Encrypt} 6 KB), while a credential may be 32 KiB. Envelope encryption keeps the KMS on the
 * small, fixed-size half of the job: it never sees the value, only the key that protects it.
 *
 * <p>The two implementations differ only in which cloud answers: {@link AliyunKeyWrapper} and
 * {@link HuaweiKeyWrapper}. Both take a self-describing blob back on unwrap, so no key version has
 * to be stored beside the value.
 */
public interface KeyWrapper {

    /** @param dataKey 32 random bytes; the result is what the database keeps alongside the value */
    byte[] wrap(byte[] dataKey);

    /** @param wrapped the bytes {@link #wrap} returned */
    byte[] unwrap(byte[] wrapped);
}
