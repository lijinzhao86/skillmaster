package com.skillmasterai.api;

import com.skillmasterai.modules.account.LoginDevice;
import com.skillmasterai.modules.account.WebSessionRegistry;
import com.skillmasterai.modules.token.CliSignIn;
import com.skillmasterai.modules.token.TokenRevocation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * {@link WebSessionRegistry}, and unlike the key screen there is next to nothing to orchestrate: no
 * transaction that spans a module, and each audit row is written where its revocation happens. One
 * endpoint since v4 does touch two modules — {@link #revokeOthers}, which ends the CLI logins as well
 * as the sessions — and it is still not a use case: the second call is one line with no decision in
 * it, and a class whose whole body was that call plus the first would be a layer with nothing to
 * decide. What the two have in common is that they belong above every module, which is here.
 *
 * <p><strong>Two kinds of login, one list, and the merge is here.</strong> Since v4 the screen shows
 * a CLI login beside a browser session (Q6: the same two rulers, the same action, so the same list
 * and the same count). The two live in two modules — sessions in M1, authorizations in M2 — and the
 * one layer allowed to see both is this one: a module reading another module's tables is what the
 * ownership rule forbids, and M1 must not learn about tokens. So each module answers for its own
 * kind, through its own public interface, and this controller puts the two answers in one list.
 *
 * <p>The rows are addressed differently and that is the modules' business rather than the screen's:
 * what the console needs is {@code kind}, which says which address the 退出 button posts to. Ending
 * one is therefore two routes ({@link WebRoutes#SESSION_BY_ID} and
 * {@link WebRoutes#CLI_SIGN_IN_BY_ID}) rather than a second, two-headed interpreter of the same id.
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
    private final CliSignIn cliSignIns;
    private final TokenRevocation tokens;

    WebSessionController(WebSessionRegistry sessions, CliSignIn cliSignIns, TokenRevocation tokens) {
        this.sessions = sessions;
        this.cliSignIns = cliSignIns;
        this.tokens = tokens;
    }

    /**
     * Every device this account is signed in on, current one marked and nothing else singled out.
     *
     * <p>Newest activity first. Ordering is decided here rather than by either module, because the
     * order is a property of the merged list: each source sorts its own rows, and neither can know
     * what the other contributed. The activity a row is ordered by is the same value it shows —
     * last seen for a session, last used (or the approval) for a CLI login.
     *
     * <p>The current session is identified by a boolean rather than by an id — the console needs
     * "which one do I not offer to end" and nothing more, and an id in this response would be a
     * credential in a response (accepting #6). A CLI login is never the current one: the person
     * reading this page is on a browser.
     */
    @GetMapping(WebRoutes.SESSIONS)
    ResponseEntity<SessionsResponse> list(Authentication caller, HttpServletRequest request) {
        String userId = caller.getName();
        List<Row> rows = new ArrayList<>();
        for (LoginDevice device : sessions.listFor(userId, currentSessionId(request))) {
            rows.add(new Row(device.lastSeenAt(), SessionResponse.ofBrowser(device)));
        }
        for (CliSignIn.Login login : cliSignIns.listFor(userId)) {
            rows.add(new Row(login.lastUsedAt(), SessionResponse.ofCli(login)));
        }
        // Sorted as domain values and not as the strings they were rendered into: `Instant.toString`
        // drops trailing zeros, so two instants a moment apart can produce different-length text and
        // a text comparison would order them by the digits rather than by the time.
        rows.sort(Comparator.comparing(Row::activity).reversed());
        return ResponseEntity.ok(
                new SessionsResponse(rows.stream().map(Row::response).toList()));
    }

    /** One row of the merged list, with the moment it is ordered by kept beside it. */
    private record Row(Instant activity, SessionResponse response) {
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
     * Ends every device but this one — the CLI logins too — and says how many sessions that was.
     *
     * <p><strong>That CLI logins are "devices" here is the screen's own sentence, and this is where it
     * is made true.</strong> The list counts the rows it is showing and says 「退出后，其他 N 台设备都
     * 需要重新登录」, and since v4 those rows include CLI logins (Q6: the same two rulers, the same
     * action). This endpoint ended only the browser half, which left that sentence a lie about every
     * CLI row on it and left those logins alive on machines the person had just been told to sign in
     * on again. The two kinds live in two modules and neither may read the other's tables, so the two
     * calls are composed here, in the layer allowed to see both.
     *
     * <p><strong>The exclusion is defined here and not by the caller</strong>: the registry is handed
     * the current session's id and skips it, so "all of them then put mine back" is not expressible.
     * That matters more than it looks — a person doing this is on one of the devices, and an
     * off-by-one here ends their own login on the screen that reports it. A CLI login needs no such
     * guard: the person pressing this is on a browser, so no CLI login is the one asking.
     *
     * <p><strong>Sessions first, then the tokens</strong>, the order {@code ResetPasswordUseCase} runs
     * the same pair in. The two are not one transaction — the session store commits its own writes and
     * M2's revocation is autocommit — so a failure in the second half answers an error with the
     * sessions already gone rather than pretending either call undid the other.
     *
     * <p><strong>Both halves say what they ended, in their own vocabularies</strong>: M1 writes
     * {@code session_revoke} per session (with {@code via: revoke-others}), and M2's bulk revocation
     * writes its own {@code token_revoke_all} for the user. This endpoint introduces no event of its
     * own — a second name for the same act would be a trail that describes the caller rather than the
     * thing that happened.
     *
     * <p><strong>The count is the sessions, as it was before CLI logins were on this screen.</strong>
     * It is what the console reads (and it draws nothing from it — the design's result is the rows
     * being gone), and M2's revocation answers nothing on purpose: turning it into a reporting call is
     * exactly what would widen it.
     */
    @PostMapping(WebRoutes.SESSIONS + "/revoke-others")
    ResponseEntity<SessionsRevoked> revokeOthers(Authentication caller,
            HttpServletRequest request) {
        String userId = caller.getName();
        int ended = sessions.revokeOthers(userId, currentSessionId(request));
        tokens.revokeAllFor(userId);
        return ResponseEntity.ok(new SessionsRevoked(ended));
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
