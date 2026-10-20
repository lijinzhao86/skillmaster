package com.skillmasterai.modules.version;

/**
 * Publishing or discarding a version that is not in a state where the operation means anything.
 *
 * <p>Two cases, and both are refused rather than treated as a no-op: publishing a version that was
 * discarded, and discarding one that is not a draft. Neither is worth a code of its own on the wire
 * — it is 400 {@code invalid_request}, the same as publishing to a soft-deleted name — but the
 * message says which state it found, because the caller is the author looking at their own skill
 * and a bare 404 would send them looking for a version that is right there.
 *
 * <p>It is deliberately <em>not</em> how an absent version is reported. That is a 404 with no
 * explanation, together with every other address that resolves to nothing (§4.1).
 */
public class VersionStateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final com.skillmasterai.common.ErrorCode code;

    public VersionStateException(String message) {
        this(message, com.skillmasterai.common.ErrorCode.INVALID_REQUEST);
    }

    /**
     * The same refusal with a code of its own.
     *
     * <p>Since v6 several refusals are worth telling apart on the wire — a frozen name is a
     * different next step from a wrong state, and the CLI switches on the code to say which. The
     * default stays {@code invalid_request} so the older callers keep their answer.
     */
    public VersionStateException(String message, com.skillmasterai.common.ErrorCode code) {
        super(message);
        this.code = code;
    }

    public com.skillmasterai.common.ErrorCode code() {
        return code;
    }
}
