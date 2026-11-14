package com.skillmasterai.api;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.token.CliSignIn;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CLI's question, from any machine with a network connection (v68, ADR 0190).
 *
 * <p><strong>Three routes, and none of them needs a session.</strong> The caller is a process on the
 * person's machine that has no credential — that is what it is asking for. What keeps this from
 * being an open door is the shape of each route: creation makes one short-lived row with no
 * authorization behind it, the view returns only what a page may show, and the poll needs a secret
 * plus a PKCE verifier that only the asking process holds. All three carry an address-keyed limit.
 *
 * <p><strong>No class-level mapping.</strong> Every other controller here is
 * {@code @RequestMapping(WebRoutes.BASE)}; these answer at their own address, and putting them on
 * that plane would mean either endpoints the CLI cannot reach or a {@code permitAll} spanning
 * {@code /web/**} and unauthenticating everything behind it.
 */
@RestController
class CliDeviceSignInController {

    private static final Logger log = LoggerFactory.getLogger(CliDeviceSignInController.class);

    private final CliSignIn cliSignIns;
    private final AccountDirectory accounts;

    CliDeviceSignInController(CliSignIn cliSignIns, AccountDirectory accounts) {
        this.cliSignIns = cliSignIns;
        this.accounts = accounts;
    }

    /** The machine asks. Created rather than OK: this is the request that brings a question into being. */
    @PostMapping(WebRoutes.CLI_DEVICE_SIGN_INS)
    ResponseEntity<CliDeviceSignInStartedResponse> start(@RequestBody StartRequest body,
            HttpServletRequest request) {
        if (body.name() == null || body.name().isBlank()) {
            throw new CliSignInRequestException("name is required — it is what the machine is called"
                    + " on the console, and how a repeated login finds the one it replaces");
        }
        if (body.codeChallenge() == null || body.codeChallenge().isBlank()) {
            throw new CliSignInRequestException("code_challenge is required — the credential is"
                    + " collected only by the process that can prove it holds the verifier");
        }
        CliSignIn.Started started = cliSignIns.start(new CliSignIn.Start(
                body.name().trim(), body.clientId(), body.codeChallenge(), body.scopes()),
                request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(CliDeviceSignInStartedResponse.of(started));
    }

    /**
     * The page reads the question.
     *
     * <p><strong>No state changes here.</strong> A link preview in a chat application will fetch this
     * address; that must not spend the ticket, mark it seen or affect the approval in any way. The
     * response is also marked not to be kept and not to leak the address it was fetched from.
     */
    @GetMapping(WebRoutes.CLI_DEVICE_SIGN_IN)
    ResponseEntity<CliDeviceSignInViewResponse> view(@PathVariable String ticket,
            HttpServletRequest request) {
        CliSignIn.PublicView view = cliSignIns.view(ticket, request.getRemoteAddr())
                .orElseThrow(CodeNotFoundException::new);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .body(CliDeviceSignInViewResponse.of(view));
    }

    /**
     * The waiting process gives up: Ctrl-C ends the question, not only the wait.
     *
     * <p><strong>Why this route exists at all.</strong> Acceptance #3: after Ctrl-C the link must not
     * still be approvable. Only this process knows the cancellation happened, and the device secret
     * is what proves the request comes from it. Answers 204 for a question that is already over as
     * well — the caller has stopped waiting, and a cancellation is not worth an error.
     */
    @PostMapping(WebRoutes.CLI_DEVICE_SIGN_IN_CANCEL)
    ResponseEntity<Void> cancel(@PathVariable String ticket, @RequestBody CancelRequest body) {
        boolean mine = cliSignIns.cancel(ticket, body.deviceSecret());
        if (!mine) {
            throw new CodeNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * The machine asks whether the answer arrived — and, when it did, collects the credential.
     *
     * <p><strong>No session, and that is the design rather than an oversight.</strong> What stands in
     * for it is the secret handed to this process once, plus the PKCE verifier it has held since it
     * asked. A ticket alone — the part that travels in a link — cannot collect anything.
     *
     * <p>{@code no-store} on every answer, because one of them is a bearer value and the cheapest way
     * to keep it out of anything sitting between here and the machine is to forbid keeping it.
     */
    @PostMapping(WebRoutes.CLI_DEVICE_SIGN_IN_TOKEN)
    ResponseEntity<CliDevicePollResponse> poll(@PathVariable String ticket,
            @RequestBody PollRequest body, HttpServletRequest request) {
        CliSignIn.Poll poll = cliSignIns.poll(ticket, body.deviceSecret(), body.codeVerifier(),
                        request.getRemoteAddr())
                .orElseThrow(CodeNotFoundException::new);
        ResponseEntity.BodyBuilder answer = poll.status().equals(CliSignIn.APPROVED)
                ? ResponseEntity.ok()
                : poll.status().equals(CliSignIn.PENDING)
                        ? ResponseEntity.status(HttpStatus.ACCEPTED)
                        : ResponseEntity.status(HttpStatus.GONE);
        CliDevicePollResponse response = poll.status().equals(CliSignIn.APPROVED)
                ? CliDevicePollResponse.approved(poll.credential(), usernameOf(poll.credential().userId()))
                : CliDevicePollResponse.of(poll.status());
        return answer.header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
    }

    /**
     * The handle behind the id on the authorization row.
     *
     * <p>M1 owns {@code app_user.handle}, and this is the same call {@code /api/v1/me} makes for the
     * same reason. A missing row here is the broken invariant it is there: the poll just read a live
     * authorization for this id a moment ago, so the account exists. Answered rather than thrown so a
     * terminal gets the credential it is owed, with the one sentence about the account omitted
     * instead of a 500.
     */
    private String usernameOf(String userId) {
        try {
            return accounts.handleOf(userId).orElse(null);
        } catch (RuntimeException e) {
            // The credential has already been minted and saved by the time this runs. A failed
            // lookup must not turn that into a 500 the terminal never sees.
            log.warn("could not resolve the handle for user {}; the credential was issued without"
                    + " a username", userId, e);
            return null;
        }
    }

    /**
     * @param name          what the machine suggests calling itself; the page may change it
     * @param clientId      which registered client this is for
     * @param codeChallenge the PKCE challenge the poll must match
     * @param scope         space-separated, the spelling the credential carries
     */
    record StartRequest(String name, String clientId, String codeChallenge, String scope) {

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
     * @param deviceSecret the value returned to the CLI once, never in a URL
     * @param codeVerifier the PKCE verifier whose digest has to be the challenge the question was
     *                     created with
     */
    record PollRequest(String deviceSecret, String codeVerifier) {
    }

    /** A cancellation carries the one thing that proves it is the process that asked. */
    record CancelRequest(String deviceSecret) {
    }
}
