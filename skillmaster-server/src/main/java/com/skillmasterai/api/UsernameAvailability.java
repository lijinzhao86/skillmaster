package com.skillmasterai.api;

import java.util.Optional;

/**
 * Whether a candidate username can be taken, and if not, why.
 *
 * <p>An answer about a name rather than a refusal, so it is a 200 with a body: a form asking as
 * somebody leaves the field has asked a question, and "no" is an answer to it. The envelope's error
 * shape is for requests that were wrong, and this one was not — a taken name is exactly what the
 * endpoint is for.
 *
 * <p>{@code issue} carries the server's own code so the client words it with the same sentence it
 * would use for the same refusal at submit. Null when the name is free.
 */
record UsernameAvailability(boolean available, String issue) {

    static UsernameAvailability of(Optional<String> issue) {
        return new UsernameAvailability(issue.isEmpty(), issue.orElse(null));
    }
}
