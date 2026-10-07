package com.skillmasterai.modules.account;

/**
 * The caller has spent its budget for this window.
 *
 * <p>Carries how long the window has left, because that is the only part of the answer a client
 * can act on, and it goes out as {@code Retry-After}. A refusal that omits it invites exactly the
 * retry loop the limit exists to stop.
 */
public final class ThrottledException extends RuntimeException {

    private final long retryAfterSeconds;

    public ThrottledException(long retryAfterSeconds) {
        super("too many requests; retry in " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
