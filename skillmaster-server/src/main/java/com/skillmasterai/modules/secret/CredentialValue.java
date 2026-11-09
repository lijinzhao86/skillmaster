package com.skillmasterai.modules.secret;

import java.util.Arrays;

/**
 * The plaintext half of a credential, on its way in — never stored, never returned.
 *
 * <p>The 32 KiB ceiling is enforced here rather than in a use case because every write path
 * (create and rotate) has to agree on it, and a value that is too large is a fact about the value
 * itself rather than about the operation. The limit is the size of an environment variable a
 * process can reasonably receive: an ordinary API key, a cookie, a small PEM and a JSON blob all
 * fit, while a whole credential file does not.
 *
 * <p>The byte array is copied in and copied out. A record component of type {@code byte[]} would
 * otherwise be shared with whoever handed it over, and a caller that zeroed or edited it after the
 * call would be editing what the sealer is about to encrypt.
 */
public record CredentialValue(byte[] bytes) {

    public static final int MAX_BYTES = 32 * 1024;

    public CredentialValue {
        if (bytes == null) {
            throw new IllegalArgumentException("a credential value needs bytes");
        }
        bytes = Arrays.copyOf(bytes, bytes.length);
        if (bytes.length > MAX_BYTES) {
            throw new SecretValueTooLargeException(bytes.length, MAX_BYTES);
        }
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    public int byteLength() {
        return bytes.length;
    }

    /** The convenience constructor for the ordinary case: UTF-8 text. */
    public static CredentialValue of(String text) {
        return new CredentialValue(text == null ? new byte[0]
                : text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
