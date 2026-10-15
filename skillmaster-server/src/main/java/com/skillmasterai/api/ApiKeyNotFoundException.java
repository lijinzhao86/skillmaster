package com.skillmasterai.api;

/**
 * The id names no key of the caller's — including the case where it names somebody else's.
 *
 * <p><strong>One answer for both, and the collapse is the point</strong> (§4.2's rule, applied
 * here): a distinct answer for "that key exists but is not yours" would confirm that a key with that
 * id exists. Nothing is lost by it either, because the console only ever addresses keys the caller
 * was just looking at.
 *
 * <p>Thrown from the API layer rather than returned as an empty {@code Optional}, in the shape
 * {@link SkillNotFoundException} already has: the use cases answer with an absence — they have no
 * business knowing about HTTP — and the translation to a status and a code happens here.
 */
class ApiKeyNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ApiKeyNotFoundException() {
        super("no key with that id");
    }
}
