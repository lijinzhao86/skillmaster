package com.skillmasterai.api;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.token.CliSignIn;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ending the login the caller is holding — the API plane's one way out (v46, ADR 0142).
 *
 * <p><strong>No id in the request, and that is the whole design.</strong> The credential already
 * knows which authorization it was minted under (the token row names it), so the route has nothing
 * to address and nothing to validate: a caller cannot end somebody else's login because it has no
 * way to name one. That also makes it idempotent in the only sense that matters — reaching it again
 * with the same credential means the login is already over, which is the state the command wants.
 *
 * <p><strong>Why it exists at all.</strong> Before v46, {@code skmr logout} cleared the local file
 * and left the server-side login alive, so the console's 「登录设备」 kept a row for a machine that
 * thought it was signed out. ADR 0038 §决定 7 said that on purpose, for API keys; ADR 0142 narrows
 * it — only the login credential ends here, and a key is still revoked from the console.
 *
 * <p><strong>An API key gets 400, not 204.</strong> A key has no login to end; answering success
 * would claim an effect it did not have. The CLI never sends a key here (it checks the credential
 * kind first), so this is the answer to a hand-written request rather than a state the product
 * reaches.
 */
@RestController
class LogoutController {

    static final String PATH = "/api/v1/logout";

    private final CliSignIn cliSignIns;

    LogoutController(CliSignIn cliSignIns) {
        this.cliSignIns = cliSignIns;
    }

    /**
     * Ends the authorization behind the presented token.
     *
     * <p>The token has already passed every liveness check by the time this runs — the security
     * filter validated it — so no second judgement is made here. A token whose login was ended by
     * the console is refused there with {@code token_revoked}, which is the answer the CLI treats as
     * "already over" rather than "failed".
     */
    @PostMapping(PATH)
    ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedSubject subject) {
        if (subject.authorizationId() == null) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "this route ends a CLI login; this credential does not name one");
        }
        cliSignIns.revokeSelf(subject.userId(), subject.authorizationId());
        return ResponseEntity.noContent().build();
    }
}
