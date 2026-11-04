package com.skillmasterai.api;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.notification.NotificationNotFoundException;
import com.skillmasterai.modules.notification.NotificationStateException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The two notification errors (v45), kept beside the module they belong to rather than growing the
 * shared handler past its size limit.
 *
 * <p>Highest precedence because the shared advice's catch-all {@code Exception} handler would
 * otherwise answer these first and turn a 404 into a 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class NotificationExceptionAdvice {

    /** No such notification, or not this caller's. One answer for both, like every other 404. */
    @ExceptionHandler(NotificationNotFoundException.class)
    ResponseEntity<ApiError> onNotificationNotFound(NotificationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ErrorCode.NOTIFICATION_NOT_FOUND, e.getMessage()));
    }

    /** A notification that exists and cannot be answered now: 409 with the reason in the detail. */
    @ExceptionHandler(NotificationStateException.class)
    ResponseEntity<ApiError> onNotificationState(NotificationStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(ErrorCode.INVALID_NOTIFICATION_STATE, e.getMessage(),
                        "reason", e.reason()));
    }
}
