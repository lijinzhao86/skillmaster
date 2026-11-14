package com.skillmasterai.api;

import com.skillmasterai.modules.token.CliSignIn;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ending a CLI login from the console (v4; the question itself moved in v68).
 *
 * <p><strong>One write under {@code /web}, and the identity comes from the session.</strong> What a
 * caller may end is decided by who it is, not by what it sends — the same rule every other write on
 * this plane keeps. The two routes that used to live here — the approval that started a login and
 * the refusal that sent the browser back — are now {@link CliDeviceApprovalController}'s; a login is
 * answered before it exists, not after.
 */
@RestController
@RequestMapping(WebRoutes.BASE)
class CliSignInController {

    private final CliSignIn cliSignIns;

    CliSignInController(CliSignIn cliSignIns) {
        this.cliSignIns = cliSignIns;
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
}
