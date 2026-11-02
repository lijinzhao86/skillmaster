package com.skillmasterai.api;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.ingest.IngestException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A rejected upload, on its own advice because a size refusal answers 413.
 *
 * <p>The decision is by issue rather than by a new exception type: the issue names are the contract
 * clients already switch on, and a domain exception carrying an HTTP status would put the transport
 * into the ingest module. Split from {@link ApiExceptionHandler} when that file crossed its limit.
 *
 * <p><strong>It must be consulted first.</strong> {@link ApiExceptionHandler} ends with a handler for
 * {@code Exception} that answers 500, and advices are consulted one after another — without the
 * order, that catch-all claims an upload refusal before this class sees it.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class UploadExceptionAdvice {

    /**
     * A rejected upload. Carries every problem found, so one round trip is enough to fix it.
     *
     * <p>A size refusal answers 413 (technical-design §错误) and every other refusal 400. The
     * decision is by issue rather than by a new exception type: the issue names are the contract
     * clients already switch on, and a domain exception carrying an HTTP status would put the
     * transport into the ingest module.
     */
    @ExceptionHandler(IngestException.class)
    ResponseEntity<ApiError> onIngestException(IngestException e) {
        boolean tooLarge = e.details().stream()
                .anyMatch(detail -> "file_too_large".equals(detail.issue())
                        || "package_too_large".equals(detail.issue()));
        return ResponseEntity.status(tooLarge ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.BAD_REQUEST)
                .body(ApiError.of(ErrorCode.INVALID_UPLOAD, e.getMessage(), e.details()));
    }

}
