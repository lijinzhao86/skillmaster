package com.skillmasterai.common;

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

    public RequestException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
