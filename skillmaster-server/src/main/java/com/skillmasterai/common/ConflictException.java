package com.skillmasterai.common;

import java.util.List;

/**
 * A request that is well-formed and conflicts with the state of the thing it names — 409.
 *
 * <p><strong>Its own type because {@link RequestException}'s handler is fixed at 400.</strong>
 * Retargeting a body-carried request is a 400, and neither of v7's content conflicts is one: an
 * {@code If-Match} that no longer matches is the caller's signal to re-read (409
 * {@code digest_mismatch}), and content that is already another version's is a fact about the skill
 * (409 {@code content_already_exists}). Both are retryable after the caller looks, which is exactly
 * what a 409 tells a client. The code travels with the message because the CLI switches on it.
 *
 * <p>{@code details} is where the useful half lives — the current digest, the version a colliding
 * body copies — and it has the same shape as every other field-level detail in the API.
 */
public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final List<ApiError.Detail> details;

    public ConflictException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ConflictException(ErrorCode code, String message, List<ApiError.Detail> details) {
        super(message);
        this.code = code;
        this.details = List.copyOf(details);
    }

    public ConflictException(ErrorCode code, String message, String field, String issue) {
        this(code, message, List.of(new ApiError.Detail(field, issue)));
    }

    public ErrorCode code() {
        return code;
    }

    public List<ApiError.Detail> details() {
        return details;
    }
}
