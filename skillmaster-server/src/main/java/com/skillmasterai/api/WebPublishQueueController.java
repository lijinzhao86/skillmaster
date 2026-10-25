package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.ReadPublishQueueUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The publish queue (v18): a read, and the only new read this version adds.
 *
 * <p><strong>It is not {@code /web/skills}.</strong> That read answers "what do I have", one row per
 * skill, carrying the draft count and the newest draft's digest — which is not enough to build a
 * page whose row is a draft. See TD §接口 for the three things it cannot answer.
 *
 * <p>The identity comes from the session as everywhere on this plane, and the rung filter happens in
 * the use case rather than here: a row the caller cannot publish must not reach the page at all.
 */
@RestController
@RequestMapping(path = WebRoutes.BASE)
class WebPublishQueueController {

    private final ReadPublishQueueUseCase publishQueue;

    WebPublishQueueController(ReadPublishQueueUseCase publishQueue) {
        this.publishQueue = publishQueue;
    }

    @GetMapping(WebRoutes.PUBLISH_QUEUE)
    ResponseEntity<PublishQueueResponse> queue(Authentication caller) {
        return ResponseEntity.ok(PublishQueueResponse.of(
                publishQueue.queue(AuthenticatedSubject.ofSession(caller.getName()))));
    }
}
