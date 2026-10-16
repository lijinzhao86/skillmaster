package com.skillmasterai.api;

/**
 * The id names no login device of the caller's — including the case where it names somebody else's.
 *
 * <p><strong>One answer for both, and the collapse is the point</strong> (§4.2's rule, applied here
 * as it is to keys and skills): a distinct answer for "that device exists but is not yours" would
 * confirm that a device with that id exists. Nothing is lost by it either — the console only ever
 * addresses rows it was just looking at, and a row that has since ended elsewhere deserves the same
 * 404 as one that was never there.
 *
 * <p>Thrown from the API layer rather than returned as a false from the registry, in the shape
 * {@link ApiKeyNotFoundException} already has: the module answers with an absence — it has no
 * business knowing about HTTP — and the translation to a status and a code happens here.
 */
class SessionNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SessionNotFoundException() {
        super("no login device with that id");
    }
}
