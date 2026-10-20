package com.skillmasterai.modules.version;

/**
 * A consumer pinned a version that was published and later taken down (ADR 0058).
 *
 * <p><strong>410, not 404.</strong> The caller holds the pin, so "there was a version here and it
 * was withdrawn" is the honest and more useful answer; 404 would read as "your address is wrong"
 * and send them looking for a typo. Nothing is disclosed that the pin did not already name: the
 * version name was in the address they were given.
 *
 * <p>Thrown only on the consumption plane. The author plane shows a revoked row to {@code maintain}
 * and above as an ordinary row with a status, not as an error.
 */
public final class VersionRevokedException extends RuntimeException {

    public VersionRevokedException(String message) {
        super(message);
    }
}
