package com.skillmasterai.api;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.token.CliSignIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redeeming a sign-in code for the credential it carries — the one public write on this server.
 *
 * <p><strong>No session, and that is the design rather than an oversight.</strong> The caller is a
 * process on the person's own machine: it has no cookie, and it cannot be given one without making it
 * a browser. What stands in for the session is what the code is — minted only by a session, single
 * use, minutes long, and bound to a PKCE challenge the caller proves it holds. Being outside
 * {@code /web} is also why the request carries its own rate limit (v4 §接口).
 *
 * <p><strong>Class-level mapping deliberately absent.</strong> Every other controller here is
 * {@code @RequestMapping(WebRoutes.BASE)}; this route answers at its own address, and putting it on
 * that plane would mean either an endpoint the CLI cannot reach or a {@code permitAll} spanning
 * {@code /web/**} and unauthenticating everything behind it.
 */
@RestController
class CliSignInExchangeController {

    private static final Logger log = LoggerFactory.getLogger(CliSignInExchangeController.class);

    private final CliSignIn cliSignIns;
    private final AccountDirectory accounts;

    CliSignInExchangeController(CliSignIn cliSignIns, AccountDirectory accounts) {
        this.cliSignIns = cliSignIns;
        this.accounts = accounts;
    }

    /**
     * The credential, once.
     *
     * <p>{@code no-store} on the way out, because the body is a bearer value and a response that may
     * not be kept is the cheapest way to keep it out of anything sitting between here and the machine
     * that asked. Nothing else on this plane sets it — nothing else here answers with a credential.
     */
    @PostMapping(WebRoutes.CLI_SIGN_IN_EXCHANGE)
    ResponseEntity<CliSignInCredentialResponse> exchange(@RequestBody ExchangeRequest body,
            HttpServletRequest request) {
        return cliSignIns.exchange(body.code(), body.codeVerifier(), request.getRemoteAddr())
                .map(credential -> CliSignInCredentialResponse.of(credential,
                        usernameOf(credential.userId())))
                .map(credential -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(credential))
                .orElseThrow(CodeNotFoundException::new);
    }

    /**
     * The handle behind the id on the authorization row.
     *
     * <p>M1 owns {@code app_user.handle}, and this is the same call {@code /api/v1/me} makes for the
     * same reason. A missing row here is the broken invariant it is there: the exchange just read a
     * live authorization for this id a moment ago, so the account exists. Answered rather than
     * thrown so a terminal gets the credential it is owed, with the one sentence about the account
     * omitted instead of a 500.
     */
    private String usernameOf(String userId) {
        try {
            return accounts.handleOf(userId).orElse(null);
        } catch (RuntimeException e) {
            // **The credential has already been minted and saved by the time this runs.** A failed
            // lookup here must not turn that into a 500 the terminal never sees: the login would be
            // live on the server while the caller holds nothing, which is worse than one missing
            // sentence. The field is optional to the client for exactly this reason — but the
            // failure is still logged, because a schema or connection problem that silently drops
            // the account sentence on every login would otherwise be invisible.
            log.warn("could not resolve the handle for user {}; the credential was issued without"
                    + " a username", userId, e);
            return null;
        }
    }

    /**
     * @param codeVerifier the PKCE verifier whose digest has to be the challenge the code was created
     *                     with. It travels in the body rather than the address, which is the whole
     *                     reason the challenge could travel in the address
     */
    record ExchangeRequest(String code, String codeVerifier) {
    }
}
