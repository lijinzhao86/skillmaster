package com.skillmasterai.api;

import com.skillmasterai.modules.account.WebSessionRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.1's browser plane for logins: {@code /web/sessions} (ADR 0043).
 *
 * <p><strong>Seeing your own logins and ending them is a session-plane act, and that is why there is
 * no {@code /api/v1} twin of this.</strong> A credential cannot end a browser session — the two are
 * different kinds of thing (ADR 0043 §决定 3) — and the reverse is what makes this plane the safe
 * one to hold the power: whoever calls this is a person at a browser, not a script holding a
 * string.
 *
 * <p><strong>Three endpoints and no use case.</strong> §架构 draws this controller straight onto
 * {@link WebSessionRegistry}, and unlike the key screen there is nothing to orchestrate: no second
 * module to write, no transaction that spans one, and the audit row is written where the revocation
 * happens. A use case here would be a class whose whole body is a call.
 *
 * <p>The routes are also the contract with the console's page address ({@code /account/sessions}),
 * for the reason {@link WebKeyController} gives about its own: a page address that moves stops
 * matching the strings that name it, and {@code WebRoutes.SESSIONS} is where this side of that pair
 * lives.
 */
@RestController
@RequestMapping(path = WebRoutes.BASE)
class WebSessionController {

    private final WebSessionRegistry sessions;

    WebSessionController(WebSessionRegistry sessions) {
        this.sessions = sessions;
    }

    /**
     * Every device this account is signed in on, current one marked and nothing else singled out.
     *
     * <p>Newest activity first, which the registry decides. The current session is identified by a
     * boolean rather than by an id — the console needs "which one do I not offer to end" and nothing
     * more, and an id in this response would be a credential in a response (accepting #6).
     */
    @GetMapping(WebRoutes.SESSIONS)
    ResponseEntity<SessionsResponse> list(Authentication caller, HttpServletRequest request) {
        return ResponseEntity.ok(new SessionsResponse(
                sessions.listFor(caller.getName(), currentSessionId(request)).stream()
                        .map(SessionResponse::of)
                        .toList()));
    }

    /**
     * Ends one device's login, now. 204, like the key screen's revoke.
     *
     * <p>The row is gone before this answers, so the next request from that device is anonymous —
     * there is nothing to wait for and no sweep involved (accepting #4). Ending a device that is
     * already gone is a 404 rather than a second 204: unlike a key, a device is not something the
     * console looks at after it is gone, so a stale row means the page is out of date and should say
     * so. The id names the current session's device as happily as any other — the design removes that
     * control from the row rather than the server refusing it, because the device is real and only
     * the button would be a mistake.
     */
    @PostMapping(WebRoutes.SESSION_BY_ID + "/revoke")
    ResponseEntity<Void> revoke(@PathVariable String id, Authentication caller) {
        if (!sessions.revoke(caller.getName(), id)) {
            throw new SessionNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Ends every device but this one, and says how many that was.
     *
     * <p><strong>The exclusion is defined here and not by the caller</strong>: the registry is handed
     * the current session's id and skips it, so "all of them then put mine back" is not expressible.
     * That matters more than it looks — a person doing this is on one of the devices, and an
     * off-by-one here ends their own login on the screen that reports it.
     */
    @PostMapping(WebRoutes.SESSIONS + "/revoke-others")
    ResponseEntity<SessionsRevoked> revokeOthers(Authentication caller,
            HttpServletRequest request) {
        return ResponseEntity.ok(
                new SessionsRevoked(sessions.revokeOthers(caller.getName(), currentSessionId(request))));
    }

    /**
     * The session this request is being made on — the one thing every endpoint here needs and none
     * of them may send.
     *
     * <p><strong>Null cannot happen on these routes, and it is refused rather than tolerated.</strong>
     * All three are behind {@code authenticated()}, and the context that satisfied it was read out of
     * this session. Tolerating a null would mean answering "which device is mine" with "none of them",
     * which turns the exclusion above into "all of them" — a silent way to log the caller out while
     * reporting success. Refusing is the honest failure, and it names the invariant that broke.
     */
    private static String currentSessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new IllegalStateException(
                    "a request reached " + WebRoutes.SESSIONS + " with no session, but these routes"
                            + " are authenticated and that context lives in one");
        }
        return session.getId();
    }
}
