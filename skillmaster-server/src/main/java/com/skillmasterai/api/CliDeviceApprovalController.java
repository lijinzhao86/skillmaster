package com.skillmasterai.api;

import com.skillmasterai.modules.token.CliSignIn;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CLI's question on the browser plane: the person agreeing, and the person declining (v68).
 *
 * <p><strong>Two writes under {@code /web}, and both take the identity from the session.</strong>
 * That is the whole security story: what a question may become is decided by who is signed in, not
 * by what the request sends. The approval writes the authorization row in the same transaction that
 * marks the question answered; the refusal writes nothing.
 *
 * <p><strong>Why {@code 409} and not {@code 404} for a question that is gone.</strong> The page
 * reached this ticket from a link, so its own address may be expired or already used — a state the
 * screen draws. {@code 409} says "this question can no longer be answered"; {@code 404} would say
 * the route does not exist, which is a different thing to tell a browser.
 */
@RestController
@RequestMapping(WebRoutes.BASE)
class CliDeviceApprovalController {

    private final CliSignIn cliSignIns;

    CliDeviceApprovalController(CliSignIn cliSignIns) {
        this.cliSignIns = cliSignIns;
    }

    /**
     * The click: records the consent and writes the authorization row.
     *
     * <p>No content rather than a body: the browser has nothing to redeem, and the credential is
     * collected by the CLI that asked — not by the page that answered.
     */
    @PostMapping(WebRoutes.CLI_DEVICE_APPROVE)
    ResponseEntity<Void> approve(@PathVariable String ticket, @RequestBody ApproveRequest body,
            Authentication caller, HttpServletRequest request) {
        if (body.name() == null || body.name().isBlank()) {
            throw new CliSignInRequestException("name is required — it is what the machine is called"
                    + " on the console, and how a repeated login finds the one it replaces");
        }
        if (!cliSignIns.approve(caller.getName(), ticket, body.name(), request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.noContent().build();
    }

    /** The other button: no login happens, and the question is closed. */
    @PostMapping(WebRoutes.CLI_DEVICE_REFUSE)
    ResponseEntity<Void> refuse(@PathVariable String ticket) {
        if (!cliSignIns.refuse(ticket)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * @param name the machine's name, collected on the page. Required: a row without one has nothing
     *             in 「登录设备」 for the person to recognise
     */
    record ApproveRequest(String name) {
    }
}
