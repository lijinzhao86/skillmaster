package com.skillmasterai.api;

/**
 * The answer to a share: who it went to, and the role that now applies.
 *
 * <p>Carries the role so that a page with several rows can render the result without a second
 * request, and echoes the handle so it knows which row moved.
 *
 * <p><strong>There is no equivalent of this for a withdrawal, and the omission is the design.</strong>
 * {@code DELETE …/grants/{handle}} answers {@code 204} on both planes — the request asks for a state
 * ("this person must not have access") that holds whether or not a row was there, so it is a state
 * converging rather than a resource being deleted. A body saying which it was would invite a client
 * to branch on a distinction it does not act on: the page re-reads its list either way, and the one
 * caller that does need the distinction — the audit trail — reads it from the use case, not the wire
 * (§4.3).
 *
 * @param handle what the caller typed, echoed back so a page with several rows knows which one moved
 */
record ShareResponse(String handle, String role) {

    static ShareResponse granted(String handle, String role) {
        return new ShareResponse(handle, role);
    }
}
