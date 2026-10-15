package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.token.ApiKeyPolicy;
import com.skillmasterai.usecase.IssueApiKeyUseCase;
import com.skillmasterai.usecase.ListApiKeysUseCase;
import com.skillmasterai.usecase.RenewApiKeyUseCase;
import com.skillmasterai.usecase.RevokeApiKeyUseCase;
import java.time.Instant;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.1's browser plane for credentials: {@code /web/keys} (ADR 0038).
 *
 * <p><strong>Issuing lives here and nowhere else.</strong> The API plane carries no endpoint that
 * creates, revokes or renews a key, and that is a decision rather than an omission: what may create
 * a credential is a browser session — a thing a person is doing right now — and a request path that
 * could mint keys from a key is a request path where a leak cannot be contained (ADR 0040 §决定 1).
 *
 * <p>Thin, like {@link SkillsController}: the four decisions this plane makes about a key are in the
 * use cases, and the one thing left here is the translation from an absence to a 404 — see
 * {@link ApiKeyNotFoundException}.
 *
 * <p><strong>The routes are the contract with the CLI's error messages.</strong> When a key expires,
 * the terminal says where to renew it, and it names this page by its path. Moving it means moving
 * that string, which is why the path is a constant in {@link WebRoutes} with the reason written on it.
 */
@RestController
@RequestMapping(path = WebRoutes.BASE)
class WebKeyController {

    private final ListApiKeysUseCase listKeys;
    private final IssueApiKeyUseCase issueKey;
    private final RevokeApiKeyUseCase revokeKey;
    private final RenewApiKeyUseCase renewKey;
    private final ApiKeyPolicy policy;

    WebKeyController(ListApiKeysUseCase listKeys, IssueApiKeyUseCase issueKey,
            RevokeApiKeyUseCase revokeKey, RenewApiKeyUseCase renewKey, ApiKeyPolicy policy) {
        this.listKeys = listKeys;
        this.issueKey = issueKey;
        this.revokeKey = revokeKey;
        this.renewKey = renewKey;
        this.policy = policy;
    }

    /**
     * Every key this account has, revoked ones included and marked: they are not the same as gone.
     *
     * <p>The policy rides along because the create form needs it and has nowhere else to get it —
     * {@link ApiKeysResponse.Policy} says why the console cannot simply assume the design's 90 days.
     */
    /**
     * The last day this deployment issues, as the console's date input wants it, or null when expiry
     * is off. Read from the policy rather than derived by the client: the two clocks differ, and a
     * form that computed the bound itself would refuse its own preselected date for however far the
     * browser is ahead of the server around a day boundary (ADR 0042 §决定 5).
     */
    private static String latestDay(ApiKeyPolicy policy) {
        var day = policy.latestDay(Instant.now());
        return day == null ? null : day.toString();
    }

    @GetMapping(WebRoutes.KEYS)
    ResponseEntity<ApiKeysResponse> list(Authentication caller) {
        return ResponseEntity.ok(new ApiKeysResponse(
                listKeys.list(subject(caller)).stream().map(ApiKeyResponse::of).toList(),
                new ApiKeysResponse.Policy(policy.expire(), policy.lifetime().toDays(), latestDay(policy))));
    }

    /**
     * Issues a key and returns it with its secret — the one response that ever carries one.
     *
     * <p>201 rather than 200: this creates something at an address that did not exist. The address
     * itself is {@code /web/keys}, the collection, because the console holds the key in a screen of
     * its own rather than navigating to it; a {@code Location} header would point at a route nothing
     * serves.
     */
    @PostMapping(WebRoutes.KEYS)
    ResponseEntity<IssuedApiKeyResponse> issue(@RequestBody IssueRequest body, Authentication caller) {
        return ResponseEntity.status(HttpStatus.CREATED).body(IssuedApiKeyResponse.of(
                issueKey.issue(new IssueApiKeyUseCase.Request(
                        body.name(), body.note(), body.scopes(), body.expiresAt()), subject(caller))));
    }

    /**
     * Stops a key working, now. Idempotent, and 204 either way — a second click is not an error, it
     * is somebody making sure.
     */
    @PostMapping(WebRoutes.KEY_BY_ID + "/revoke")
    ResponseEntity<Void> revoke(@PathVariable String id, Authentication caller) {
        revokeKey.revoke(id, subject(caller)).orElseThrow(ApiKeyNotFoundException::new);
        return ResponseEntity.noContent().build();
    }

    /**
     * Replaces a key with a fresh one: same name, same note, same scopes, new date.
     *
     * <p>A write that answers with a body, unlike revoke, because the whole point is the new secret —
     * the caller has nothing until it has that, and a 204 would leave them to fetch the listing and
     * still not have it.
     */
    @PostMapping(WebRoutes.KEY_BY_ID + "/rotate")
    ResponseEntity<IssuedApiKeyResponse> rotate(@PathVariable String id, Authentication caller) {
        return renewKey.renew(id, subject(caller))
                .map(IssuedApiKeyResponse::of)
                .map(ResponseEntity::ok)
                .orElseThrow(ApiKeyNotFoundException::new);
    }

    /**
     * The subject a browser session acts as — no scopes and no key id, because a session presents
     * neither ({@link AuthenticatedSubject#ofSession}).
     */
    private static AuthenticatedSubject subject(Authentication caller) {
        return AuthenticatedSubject.ofSession(caller.getName());
    }

    /**
     * @param note      why this key is being issued. Absent or blank means nothing was written
     * @param expiresAt null asks for this deployment's default rather than meaning "never" — the
     *                  console sends the date it preselected, and a client that sends nothing gets the
     *                  design's 90 days (ADR 0039 §决定 1)
     */
    record IssueRequest(String name, String note, Set<String> scopes, Instant expiresAt) {
    }
}
