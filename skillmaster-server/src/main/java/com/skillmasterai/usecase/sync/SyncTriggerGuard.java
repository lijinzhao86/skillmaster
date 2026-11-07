package com.skillmasterai.usecase.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Compares the trigger header with the configured token in constant time.
 *
 * <p>A plain {@code equals} leaks the matching prefix through timing. Comparing hashes also hides
 * the token's length, which a raw {@link MessageDigest#isEqual} still reveals when the two arrays
 * differ in size. That is a small leak and a cheap one to close; the token is the only thing between
 * the internet's port scanners and a write path that publishes into the official namespace.
 */
public final class SyncTriggerGuard {

    /** Null when no token is configured: the endpoint answers 404 rather than comparing. */
    private final byte[] expectedDigest;

    public SyncTriggerGuard(String token) {
        this.expectedDigest = token == null || token.isBlank() ? null : digest(token);
    }

    public boolean configured() {
        return expectedDigest != null;
    }

    public boolean permits(String presented) {
        if (expectedDigest == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedDigest, digest(presented));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JRE", impossible);
        }
    }
}
