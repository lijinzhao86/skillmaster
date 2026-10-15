package com.skillmasterai.api;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.token.ApiKeys;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me} — the API plane's answer to "who am I, and what am I holding".
 *
 * <p><strong>It is the twin of {@code /web/session}.</strong> That endpoint is the browser plane's
 * only authenticated read, and it exists for two reasons: a client needs to know who it is signed in
 * as, and something on the plane has to prove the session is real. The API plane had no equivalent —
 * a caller with a token could not ask what the token was, which is a strange gap for a plane whose
 * whole subject is credentials. Three callers wanted it: {@code login --with-key} verifies a pasted
 * key before storing it, {@code auth status} reports which key is in use, and a 401 that says "this
 * key expired" is much less useful without a way to ask "which key?".
 *
 * <p><strong>It is not a management endpoint</strong>, which is how it can exist on a plane that has
 * none (§架构): it reads the caller's own two facts and changes nothing. Nothing here can create,
 * revoke or list anything — those four operations live on the browser plane, where the identity is a
 * person rather than a credential.
 *
 * <p><strong>Needs authentication but no scope.</strong> Requiring {@code skills:read} would be
 * requiring a permission to ask about oneself, and the moment it matters most is the moment
 * something is wrong. {@code SecurityConfig} carries the rule and the reasoning.
 *
 * <p>Thin, and deliberately so: two reads and no decisions. The one thing worth naming is what
 * {@code key} being null means — see {@link #keyOf}.
 */
@RestController
@RequestMapping(path = MeController.BASE)
class MeController {

    static final String BASE = "/api/v1/me";

    private final AccountDirectory accounts;
    private final ApiKeys keys;

    MeController(AccountDirectory accounts, ApiKeys keys) {
        this.accounts = accounts;
        this.keys = keys;
    }

    /**
     * @param key the key this request is authenticated with, or null when the credential is not a key
     *            — an OAuth token. Null is an answer, not a gap: the CLI says "this credential is not
     *            an API key" rather than printing a name it made up
     */
    record MeResponse(WebAccount user, ApiKeyResponse key) {
    }

    @GetMapping
    ResponseEntity<MeResponse> me(@AuthenticationPrincipal AuthenticatedSubject subject) {
        return ResponseEntity.ok(new MeResponse(user(subject), keyOf(subject)));
    }

    /**
     * The same three fields {@code /web/session} sends, built the same way, from the same record.
     *
     * <p>{@code namespace} equals {@code username} today because §3.2 makes a personal namespace's
     * slug its owner's handle, and it is sent anyway so that no client has to know that rule — the
     * argument is on {@link WebAccount}, where the shape is defined.
     *
     * <p>An account whose handle cannot be read is a broken invariant rather than a request problem:
     * the request was authenticated as this user id a moment ago, so the row exists. Answered the way
     * {@code /web/session} answers the same impossibility.
     */
    private WebAccount user(AuthenticatedSubject subject) {
        String handle = accounts.handleOf(subject.userId()).orElseThrow(() -> new IllegalStateException(
                "the credential names user " + subject.userId() + ", which has no account"));
        return new WebAccount(subject.userId(), handle, handle);
    }

    /**
     * The key behind this request, read from M2 because the subject carries only its identity.
     *
     * <p>A key that has gone missing between validation and here answers null, which is impossible in
     * practice — this request was authenticated with it microseconds ago. It is answered rather than
     * thrown because the alternative on this path is a 500 for a request that is perfectly well
     * authenticated, and the caller's next action ("check your key") is the same either way.
     */
    private ApiKeyResponse keyOf(AuthenticatedSubject subject) {
        return subject.apiKeyId() == null ? null
                : keys.find(subject.userId(), subject.apiKeyId()).map(ApiKeyResponse::of).orElse(null);
    }
}
