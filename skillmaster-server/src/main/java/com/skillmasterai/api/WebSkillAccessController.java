package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.DecideSkillAccessRequestUseCase;
import com.skillmasterai.usecase.ListSkillAccessRequestsUseCase;
import com.skillmasterai.usecase.RequestSkillAccessUseCase;
import com.skillmasterai.usecase.WithdrawSkillAccessRequestUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.1's browser plane for a skill: the author's reads, and the two writes that change what is live.
 *
 * <p><strong>Publishing exists only here</strong> (ADR 0031). It changes what every agent reading
 * through {@code /api/v1} will get, so it is reached only by a request that carries a session cookie
 * and a double-submit CSRF token — a browser a person is sitting in front of. The CLI submits and
 * then opens this page; it has no way to do this itself, which is the point rather than a gap.
 *
 * <p>The reads are the other half of the same split: they show drafts, which the consumption plane
 * cannot. They carry a session and not a token, and that is what makes them the author's view rather
 * than a wider consumption one — an agent holding a token cannot reach this controller even to read.
 *
 * <p>The caller arrives as Spring's {@link Authentication} and becomes an
 * {@link AuthenticatedSubject#ofSession} — the same id the session was established with, and no
 * scopes, because a session grants none (see {@code WebAuthentication}). Every read of that value
 * here is a {@code getName()}, which is what ADR 0014 fixed the session's principal to be.
 *
 * <p>Thin, like {@link SkillsController}: the decisions are all in the use cases, and what is left is
 * unmarshalling a body and choosing a status. An address that resolves to nothing the caller may act
 * on is §4.1's one 404, raised by the use case returning empty; a version that exists but is in the
 * wrong state is a 400 with its reason, raised by an exception and mapped where the others are.
 */
@RestController
@RequestMapping(path = WebSkillRoutes.BASE)
class WebSkillAccessController {

    private final RequestSkillAccessUseCase requestAccess;
    private final WithdrawSkillAccessRequestUseCase withdrawAccess;
    private final ListSkillAccessRequestsUseCase listAccessRequests;
    private final DecideSkillAccessRequestUseCase decideAccessRequest;

    WebSkillAccessController(RequestSkillAccessUseCase requestAccess,
            WithdrawSkillAccessRequestUseCase withdrawAccess,
            ListSkillAccessRequestsUseCase listAccessRequests,
            DecideSkillAccessRequestUseCase decideAccessRequest) {
        this.requestAccess = requestAccess;
        this.withdrawAccess = withdrawAccess;
        this.listAccessRequests = listAccessRequests;
        this.decideAccessRequest = decideAccessRequest;
    }

    /**
     * Asks for a rung. {@code 201}: a request now exists, and nothing else changed (ADR 0052).
     *
     * <p>Reached by anyone who can see the skill; the rung must be above their own, which the use
     * case refuses with a 400 rather than the 403 a capability check would give.
     *
     * <p><strong>The body is not decoration.</strong> {@code {"rung": …}} is what both clients read:
     * the console parses every 2xx as JSON and reported an empty 201 as 「服务暂时不可用」 (2026-10-26),
     * and the CLI takes the rung out of this body. The API plane's {@code POST /api/v1/…/access-requests}
     * has answered with this shape since v10; this route was the one that did not.
     */
    @PostMapping(path = WebSkillRoutes.ACCESS_REQUESTS,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccessAskedResponse> requestAccess(@PathVariable String namespace,
            @PathVariable String name, @RequestBody SkillAccessRequest request, Authentication caller) {
        requestAccess.request(namespace, name, request.rung(),
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.status(HttpStatus.CREATED).body(new AccessAskedResponse(request.rung()));
    }

    /** Withdraws the caller's own request. Idempotent, like withdrawing a share. */
    @DeleteMapping(WebSkillRoutes.MY_ACCESS_REQUEST)
    ResponseEntity<Void> withdrawAccess(@PathVariable String namespace, @PathVariable String name,
            Authentication caller) {
        withdrawAccess.withdraw(namespace, name, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }

    /** The pending requests on this skill — the {@code admin} rung's queue (ADR 0052). */
    @GetMapping(path = WebSkillRoutes.ACCESS_REQUESTS,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SkillAccessRequestsResponse> accessRequests(@PathVariable String namespace,
            @PathVariable String name, Authentication caller) {
        return ResponseEntity.ok(SkillAccessRequestsResponse.of(listAccessRequests
                .requests(namespace, name, AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new)));
    }

    /**
     * Approves one request at the rung this call carries — which may differ from the one asked for
     * (ADR 0052 §决定 3). {@code 204}: the request is gone and the grant is the answer.
     */
    @PostMapping(path = WebSkillRoutes.APPROVE_ACCESS_REQUEST,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> approveAccess(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String handle, @RequestBody SkillAccessDecisionRequest request,
            Authentication caller) {
        decideAccessRequest.decide(namespace, name, handle, request.rung(), true,
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }

    /** Denies one request: it disappears, nothing is granted. {@code 204} like an approval. */
    @PostMapping(WebSkillRoutes.DENY_ACCESS_REQUEST)
    ResponseEntity<Void> denyAccess(@PathVariable String namespace, @PathVariable String name,
            @PathVariable String handle, Authentication caller) {
        decideAccessRequest.decide(namespace, name, handle, null, false,
                        AuthenticatedSubject.ofSession(caller.getName()))
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.noContent().build();
    }

    /**
     * What {@code POST …/access-requests} answers: the rung that is now waiting.
     *
     * <p>Same shape as the API plane's {@code AccessAsked}, deliberately — one fact, one field name,
     * whichever plane asked for it.
     */
    record AccessAskedResponse(String rung) {
    }
}
