package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.secret.CredentialLease;
import com.skillmasterai.usecase.LeaseCredentialsUseCase;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint that returns plaintext, and therefore the one that must never be cached.
 *
 * <p>It lives under {@code /skills/{ns}/{name}} because that is the question it answers: "what
 * environment does <em>this run</em> need?" The caller was authorized by the metadata read; this
 * endpoint repeats the access check in its use case, because the response is the value itself and
 * must not rely on a prior request having happened.
 *
 * <p>{@code Cache-Control: no-store} is not decoration: a shared proxy or a client disk cache
 * holding this body would outlive the five-minute lease and spread the value beyond the process
 * that asked.
 */
@RestController
@RequestMapping(path = SkillRoutes.BASE)
class SkillCredentialsController {

    private final LeaseCredentialsUseCase lease;

    SkillCredentialsController(LeaseCredentialsUseCase lease) {
        this.lease = lease;
    }

    @PostMapping(path = SkillRoutes.SKILL + "/credentials/lease",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LeaseResponse> lease(@PathVariable String namespace,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        CredentialLease lease = this.lease.lease(namespace, name, subject)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new LeaseResponse(lease.values().stream()
                        .map(value -> new Value(value.env(), value.value()))
                        .toList(), lease.expiresAt()));
    }

    record Value(String env, String value) {
    }

    record LeaseResponse(List<Value> values, String expiresAt) {
        LeaseResponse {
            values = List.copyOf(values);
        }
    }
}
