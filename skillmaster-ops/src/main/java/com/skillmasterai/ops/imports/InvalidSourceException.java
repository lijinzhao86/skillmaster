package com.skillmasterai.ops.imports;

/**
 * A source declaration that cannot be resolved: an unknown kind, or a locator the adapter refuses.
 * Publishing fails rather than storing half a connection.
 */
public final class InvalidSourceException extends RuntimeException {

    public InvalidSourceException(String message) {
        super(message);
    }
}
