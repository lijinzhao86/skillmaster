package com.skillmasterai.common;

import java.util.List;

/**
 * The error body every 4xx and 5xx carries:
 * {@code {"error": {"code": ..., "message": ..., "details": [...]}}}.
 *
 * <p>Wrapped in an {@code error} object rather than spread across the top level so that a
 * successful response and an error response can never be confused for one another, and so a
 * field can be added to the envelope later without colliding with anything.
 *
 * <p>This is our own shape, not RFC 9457 {@code problem+json}. The design doc fixes only the
 * {@code WWW-Authenticate} headers for 401/403 (§4.1) and leaves the body open; keeping the body
 * to one shape across all statuses is worth more than matching a media type that nothing in the
 * client set consumes. Responses are served as {@code application/json}.
 *
 * @param code    stable, for clients to branch on
 * @param message human-readable, for logs and for whoever is debugging
 * @param details which field was wrong, when the error is about specific input
 */
public record ApiError(Error error) {

    public record Error(String code, String message, List<Detail> details) {
        public Error {
            details = List.copyOf(details);
        }
    }

    /**
     * One field-level problem.
     *
     * <p>A size refusal also carries the actual and allowed counts, so a client can write the
     * sentence in its own language with both numbers (v39 A9). Bytes and Unicode code points are
     * separate components rather than one "amount": a file limit counts bytes, while a text limit
     * counts code points (ADR 0181), and a client that read a code-point count as bytes would
     * render the wrong unit. The nullable components are omitted from JSON when absent; every other
     * detail keeps the original two-field shape.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Detail(String field, String issue, Long actualBytes, Long limitBytes,
            Long actualCodePoints, Long limitCodePoints) {

        public Detail(String field, String issue) {
            this(field, issue, null, null, null, null);
        }

        public Detail(String field, String issue, long actualBytes, long limitBytes) {
            this(field, issue, Long.valueOf(actualBytes), Long.valueOf(limitBytes), null, null);
        }

        /** A byte limit where only the ceiling is known, such as a count of files. */
        public static Detail limitBytes(String field, String issue, long limit) {
            return new Detail(field, issue, null, Long.valueOf(limit), null, null);
        }

        /** A text limit measured in Unicode code points, with the count and the cap. */
        public static Detail codePoints(String field, String issue, long actual, long limit) {
            return new Detail(field, issue, null, null, Long.valueOf(actual), Long.valueOf(limit));
        }
    }

    public static ApiError of(ErrorCode code, String message) {
        return of(code, message, List.of());
    }

    public static ApiError of(ErrorCode code, String message, List<Detail> details) {
        return new ApiError(new Error(code.wireName(), message, details));
    }

    /** For the common case of one problem, where wrapping it in a list at every call site is noise. */
    public static ApiError of(ErrorCode code, String message, String field, String issue) {
        return of(code, message, List.of(new Detail(field, issue)));
    }
}
