package com.skillmasterai.api;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.skill.SkillPublishedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one refusal the page actions add to the API plane (v37, ADR 0127).
 *
 * <p>Its own advice rather than a line in {@link ApiExceptionHandler}: that class is at its file
 * size, and this is a rule with its own reason — the skill is there and cannot be deleted by a
 * credential, which is a different answer from "no such skill".
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class PageActionExceptionHandler {

    @ExceptionHandler(SkillPublishedException.class)
    ResponseEntity<ApiError> onSkillPublished(SkillPublishedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(ErrorCode.SKILL_PUBLISHED, e.getMessage()));
    }
}
