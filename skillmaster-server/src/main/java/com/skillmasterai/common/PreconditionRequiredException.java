package com.skillmasterai.common;

/**
 * A write that must say what it is based on, and did not — 428 (RFC 6585).
 *
 * <p>v7's draft writes change one file and answer 200 or 409; without a baseline a client would
 * happily overwrite whatever the last writer left, and there would be nothing to compare. A missing
 * {@code If-Match} is therefore not "no constraint" but a request the server will not perform — and
 * 428 says exactly that, where 400 would read as a malformed request and 409 as one that lost a race
 * it never entered.
 */
public class PreconditionRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PreconditionRequiredException(String message) {
        super(message);
    }
}
