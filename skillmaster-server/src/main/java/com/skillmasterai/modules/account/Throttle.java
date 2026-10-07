package com.skillmasterai.modules.account;

/**
 * The answer to "has this caller spent its budget?".
 *
 * <p>Sealed rather than a boolean, because a refusal carries something a caller must not drop: how
 * long until the window rolls over. That becomes the {@code Retry-After} header, and a caller that
 * reads a boolean is a caller that forgets it.
 */
public sealed interface Throttle {

    record Allowed() implements Throttle {
    }

    /** @param retryAfterSeconds seconds until the window this refusal came from rolls over */
    record Refused(long retryAfterSeconds) implements Throttle {
    }
}
