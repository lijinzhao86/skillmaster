package com.skillmasterai.ops.imports;

import com.skillmasterai.ops.imports.ApiError;
import java.util.List;

/**
 * The upload is not acceptable. Carries every problem found, not just the first, so a caller
 * fixing a skill learns about all of it in one round trip.
 *
 * <p>Never thrown for something that was merely unexpected — only for input that genuinely fails
 * a stated rule. If the validator cannot decide, it must not guess: §3.3 requires no silent
 * degradation, and silently dropping a file or a field is exactly what the archived baseline did.
 */
public class IngestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<ApiError.Detail> details;

    public IngestException(String message, List<ApiError.Detail> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public IngestException(String message, String field, String issue) {
        this(message, List.of(new ApiError.Detail(field, issue)));
    }

    /** @return field-level problems, in the shape the error envelope carries */
    public List<ApiError.Detail> details() {
        return details;
    }
}
