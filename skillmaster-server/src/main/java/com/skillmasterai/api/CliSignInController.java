package com.skillmasterai.api;

import com.skillmasterai.modules.token.CliSignIn;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CLI's login on the browser plane: the person agreeing, and the console ending one (v4).
 *
 * <p><strong>Two writes under {@code /web}, and the third of the family is not here.</strong> The
 * exchange lives in {@code CliSignInExchangeController} because it has no session — it is the one
 * route on this server a caller with no cookie may write to, and that is a different security story
 * rather than a different verb. Keeping the two apart is what lets the {@code /web/**} chain stay
 * "authenticated, or a short list of anonymous endpoints" without an exception carved into its middle.
 *
 * <p>Both of these take the identity from the session rather than from the body, the way every other
 * write on this plane does: what a caller may end is decided by who it is, not by what it sends.
 */
@RestController
@RequestMapping(WebRoutes.BASE)
class CliSignInController {

    private final CliSignIn cliSignIns;

    CliSignInController(CliSignIn cliSignIns) {
        this.cliSignIns = cliSignIns;
    }

    /**
     * The click: records the consent, issues the code, and says where the browser goes next.
     *
     * <p>Created rather than OK, because this is the request that brings a login into being — the same
     * status {@code POST /web/keys} answers with for the same reason.
     */
    @PostMapping(WebRoutes.CLI_SIGN_INS)
    ResponseEntity<CliSignInStartedResponse> start(@RequestBody StartRequest body,
            Authentication caller, HttpServletRequest request) {
        if (body.name() == null || body.name().isBlank()) {
            throw new CliSignInRequestException("name is required — it is what the machine is called"
                    + " on the console, and how a repeated login finds the one it replaces");
        }
        // Trimmed, because the name is identity: to the person who typed them, " laptop" and "laptop"
        // are one machine, and two rows for one machine is the thing Q7 exists to prevent.
        CliSignIn.Started started = cliSignIns.start(caller.getName(), new CliSignIn.Start(
                body.name().trim(), body.clientId(), body.codeChallenge(), body.state(),
                body.scopes()), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(CliSignInStartedResponse.of(started));
    }

    /**
     * The screen's «拒绝»: no login, and the browser goes back to the CLI it came from.
     *
     * <p>Answered with the same shape the approval is — one "where next" for both buttons, so the page
     * never has to know two formats. What differs is entirely in the address that comes back.
     */
    @PostMapping(WebRoutes.CLI_SIGN_IN_REFUSE)
    ResponseEntity<CliSignInStartedResponse> refuse(@RequestBody RefuseRequest body) {
        return ResponseEntity.ok(CliSignInStartedResponse.of(
                new CliSignIn.Started(cliSignIns.refuse(body.clientId(), body.state()))));
    }

    /**
     * Ends one login, from the row the console is looking at.
     *
     * <p>{@link SessionNotFoundException} for both "no such login" and "not yours" — a CLI login is a
     * login device, and the console's 404 is already the one answer for both (ADR 0043 §接口).
     */
    @PostMapping(WebRoutes.CLI_SIGN_IN_BY_ID + "/revoke")
    ResponseEntity<Void> revoke(@PathVariable String id, Authentication caller) {
        if (!cliSignIns.revoke(caller.getName(), id)) {
            throw new SessionNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * What the screen posts back: the name it collected, and the parameters it read off its own
     * address.
     *
     * <p>None of the four is trusted. {@code clientId} only selects which registered row the scopes and
     * the redirect are read from, {@code scopes} is intersected with that row's, {@code codeChallenge}
     * is stored to be checked at the exchange, and {@code state} is handed back unread. A caller that
     * edits any of them narrows what it can get or breaks its own callback — it cannot widen either.
     *
     * @param scope space-separated, the spelling the credential itself carries and the one the address
     *              used, so the screen never has to know a second format
     */
    record StartRequest(String name, String clientId, String scope, String state,
            String codeChallenge) {

        /** Split on spaces, dropping the empties a repeated or trailing separator produces. */
        Set<String> scopes() {
            return scope == null || scope.isBlank()
                    ? Set.of()
                    : Arrays.stream(scope.split(" "))
                            .filter(part -> !part.isBlank())
                            .collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * The refusal: the same two parameters the screen read off its own address, and nothing else.
     *
     * <p>The name is not here, and that is the point — a refusal creates no row for a name to belong
     * to. Nothing about this request is stored.
     */
    record RefuseRequest(String clientId, String state) {
    }
}
