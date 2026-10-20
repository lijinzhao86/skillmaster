package com.skillmasterai.common;

import java.util.List;

/**
 * A request the service understood and refused for a reason it can name with its own code.
 *
 * <p>Distinct from the module-specific exceptions because some checks belong to no module — the
 * shape and length of a version's name and note are the request's rules, not M7's rows. The code is
 * the contract: the CLI switches on it to say what to fix.
 */
public class RequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final List<ApiError.Detail> details;

    public RequestException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    /**
     * @param details which input was wrong, when the refusal is about a value rather than a request
     *                as a whole — the same shape a field-level 400 carries everywhere else
     */
    public RequestException(ErrorCode code, String message, List<ApiError.Detail> details) {
        super(message);
        this.code = code;
        this.details = List.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public List<ApiError.Detail> details() {
        return details;
    }
}
