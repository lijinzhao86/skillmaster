package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.feedback.FeedbackConsent;
import com.skillmasterai.usecase.DeleteFeedbackUseCase;
import com.skillmasterai.usecase.ReadFeedbackConsentUseCase;
import com.skillmasterai.usecase.SubmitFeedbackUseCase;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The feedback plane (v64): the consent notice, one submission, one withdrawal.
 *
 * <p>Thin, like the other controllers. The consent check, the scan, the throttle and the
 * notification all happen in the use cases; what is left here is the wire shape.
 *
 * <p><strong>There is no list route.</strong> The receipt is the only handle a person needs, and it
 * arrives in the notification. An operator reads the table directly, which is what keeps this
 * plane from growing an authorisation model it does not have.
 */
@RestController
@RequestMapping(path = "/api/v1/feedback")
class FeedbackController {

    private final ReadFeedbackConsentUseCase consent;
    private final SubmitFeedbackUseCase submit;
    private final DeleteFeedbackUseCase delete;

    FeedbackController(ReadFeedbackConsentUseCase consent, SubmitFeedbackUseCase submit,
            DeleteFeedbackUseCase delete) {
        this.consent = consent;
        this.submit = submit;
        this.delete = delete;
    }

    @GetMapping(path = "/consent", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ConsentResponse> consent() {
        FeedbackConsent notice = consent.consent();
        return ResponseEntity.ok(new ConsentResponse(notice.version(), notice.notice()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReceiptResponse> submit(@RequestBody FeedbackRequest body,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        SubmitFeedbackUseCase.Result result = submit.submit(body.content(), body.skillAddress(),
                body.diagnostics(), body.consentVersion(),
                Boolean.TRUE.equals(body.allowSensitive()), subject);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ReceiptResponse(result.receipt(), result.createdAt()));
    }

    @DeleteMapping(path = "/{receipt}")
    ResponseEntity<Void> delete(@PathVariable String receipt,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        delete.delete(receipt, subject);
        return ResponseEntity.noContent().build();
    }

    /** The wire body; Jackson's global snake_case strategy turns these into the field names. */
    record FeedbackRequest(String content, String skillAddress, Map<String, Object> diagnostics,
            String consentVersion, Boolean allowSensitive) {
    }

    record ConsentResponse(String version, String notice) {
    }

    record ReceiptResponse(String receipt, String createdAt) {
    }
}
