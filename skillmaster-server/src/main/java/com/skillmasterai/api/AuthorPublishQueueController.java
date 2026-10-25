package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.ReadPublishQueueUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The publish queue on the author plane, for the CLI (v18).
 *
 * <p><strong>The same read as the console's, reached with a key instead of a session.</strong>
 * ADR 0081 requires every console operation to have a CLI counterpart, and the queue is a console
 * read; the CLI has no session, so it cannot call {@code /web/publish-queue}. The answer is
 * {@link PublishQueueResponse} in both cases — one shape, so "what is waiting" is one question with
 * one spelling rather than two that drift.
 *
 * <p><strong>It is a read, so it is {@code skills:read}.</strong> The rung filter (owner /
 * {@code maintain} / {@code admin}) happens inside the use case exactly as it does for the console:
 * a key is not a stronger identity, it is the same person with a different credential.
 *
 * <p>Not under {@code /api/v1/author/skills}: that path's second segment names a namespace, and a
 * queue is not a skill.
 */
@RestController
@RequestMapping(path = AuthorSkillRoutes.QUEUE)
class AuthorPublishQueueController {

    private final ReadPublishQueueUseCase publishQueue;

    AuthorPublishQueueController(ReadPublishQueueUseCase publishQueue) {
        this.publishQueue = publishQueue;
    }

    @GetMapping
    ResponseEntity<PublishQueueResponse> queue(@AuthenticationPrincipal AuthenticatedSubject subject) {
        return ResponseEntity.ok(PublishQueueResponse.of(publishQueue.queue(subject)));
    }
}
