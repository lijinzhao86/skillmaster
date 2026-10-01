package com.skillmasterai.api;

import com.skillmasterai.modules.account.Account;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.account.CaptchaChallenge;
import com.skillmasterai.modules.account.VerificationPurpose;
import com.skillmasterai.usecase.LoginUseCase;
import com.skillmasterai.usecase.RegisterAccountUseCase;
import com.skillmasterai.usecase.ResetPasswordUseCase;
import com.skillmasterai.usecase.SendVerificationCodeUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.1's browser plane: registration, login, logout and password reset, all of it JSON.
 *
 * <p>There is no view layer behind these routes and none is planned here. What a person sees is
 * the client's business; this plane's job is to turn credentials into a session and back again.
 *
 * <p><strong>The session is established here, in a controller, and that has a consequence worth
 * knowing.</strong> Spring Security's authentication filters rotate the session id and save the
 * context as part of what they do; nothing here goes through one, so {@link WebSession} performs
 * both steps by hand. Login works either way — it just stops being a session, or stops being a
 * fresh one, which are the two failures this plane's tests exist to catch.
 *
 * <p>Every method is thin for the same reason {@link SkillsController} is: all the decisions live
 * in the four use cases, where they can be tested without HTTP.
 */
@RestController
@RequestMapping(path = WebRoutes.BASE)
class WebAccountController {

    private final SendVerificationCodeUseCase sendVerificationCode;
    private final RegisterAccountUseCase registerAccount;
    private final LoginUseCase loginAccount;
    private final ResetPasswordUseCase resetPassword;
    private final CaptchaChallenge captchas;
    private final AccountDirectory accounts;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrf;

    WebAccountController(SendVerificationCodeUseCase sendVerificationCode,
            RegisterAccountUseCase registerAccount, LoginUseCase loginAccount,
            ResetPasswordUseCase resetPassword, CaptchaChallenge captchas,
            AccountDirectory accounts, SecurityContextRepository contexts,
            CsrfTokenRepository csrf) {
        this.sendVerificationCode = sendVerificationCode;
        this.registerAccount = registerAccount;
        this.loginAccount = loginAccount;
        this.resetPassword = resetPassword;
        this.captchas = captchas;
        this.accounts = accounts;
        this.contexts = contexts;
        this.csrf = csrf;
    }

    /**
     * A captcha to solve.
     *
     * <p>Anonymous, and the only endpoint on this plane whose whole purpose is to be called before
     * another one. It is what makes the two code-sending endpoints cost the caller something —
     * see {@link SendVerificationCodeUseCase}.
     */
    @GetMapping(WebRoutes.CAPTCHA)
    ResponseEntity<CaptchaResponse> captcha(HttpServletRequest request) {
        CaptchaChallenge.Issued issued = captchas.issue(clientIp(request));
        return ResponseEntity.ok(new CaptchaResponse(issued.id(), issued.image()));
    }

    /**
     * Sends the code that starts a registration.
     *
     * <p>204 for a number that already has an account, because the alternative is an endpoint that
     * says which numbers are registered. See {@link SendVerificationCodeUseCase}.
     */
    @PostMapping(WebRoutes.REGISTER_CODE)
    ResponseEntity<Void> requestRegistrationCode(@RequestBody CodeRequest body,
            HttpServletRequest request) {
        sendVerificationCode.send(new SendVerificationCodeUseCase.Request(
                body.phone(), VerificationPurpose.REGISTER, body.captchaId(), body.captchaAnswer()),
                clientIp(request));
        return ResponseEntity.noContent().build();
    }

    /** Registers, and signs the new account in — the second half is what saves a login round trip. */
    @PostMapping(WebRoutes.REGISTER)
    ResponseEntity<WebAccount> register(@RequestBody RegistrationRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Account account = registerAccount.register(new RegisterAccountUseCase.Request(
                body.phone(), body.code(), body.password(), body.username()));
        WebSession.establish(request, response, contexts, csrf, account.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(WebAccount.of(account));
    }

    /**
     * Signs in.
     *
     * <p>The 401 is {@link InvalidCredentialsException} for all three ways this fails. Nothing here
     * may tell them apart — see {@link com.skillmasterai.common.ErrorCode#INVALID_CREDENTIALS}.
     */
    @PostMapping(WebRoutes.LOGIN)
    ResponseEntity<WebAccount> login(@RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        Account account = loginAccount.login(body.phone(), body.password(), clientIp(request))
                .orElseThrow(InvalidCredentialsException::new);
        WebSession.establish(request, response, contexts, csrf, account.userId());
        return ResponseEntity.ok(WebAccount.of(account));
    }

    /**
     * Ends the session — the one endpoint whose CSRF protection is the point rather than a chore.
     *
     * <p>Without it, any page on the web could log a visitor out of this one. It is answered 204
     * even when there was no session, so a client whose session expired while it was idle gets the
     * same answer as one that was signed in.
     */
    @PostMapping(WebRoutes.LOGOUT)
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        WebSession.clear(request, response);
        return ResponseEntity.noContent().build();
    }

    /** Sends the code that starts a password reset. Same rules as registration's. */
    @PostMapping(WebRoutes.RESET_CODE)
    ResponseEntity<Void> requestResetCode(@RequestBody CodeRequest body,
            HttpServletRequest request) {
        sendVerificationCode.send(new SendVerificationCodeUseCase.Request(
                body.phone(), VerificationPurpose.RESET, body.captchaId(), body.captchaAnswer()),
                clientIp(request));
        return ResponseEntity.noContent().build();
    }

    /**
     * Replaces a password and ends every session the account had.
     *
     * <p>Not signed in afterwards, on purpose: the client logs in with the new password, which
     * proves to the person resetting that the password they chose is the one that works.
     */
    @PostMapping(WebRoutes.RESET)
    ResponseEntity<Void> reset(@RequestBody ResetRequest body, HttpServletRequest request) {
        resetPassword.reset(
                new ResetPasswordUseCase.Request(body.phone(), body.code(), body.password()));
        return ResponseEntity.noContent().build();
    }

    /**
     * Who the session is, and the only authenticated read on this plane.
     *
     * <p>Which makes it two things at once: how a client learns who it is signed in as, and how it
     * obtains the CSRF token, since that token is written to a cookie on the first response that
     * goes through the chain. Without an endpoint like this behind {@code authenticated()},
     * nothing on the plane would ever prove the session is real.
     */
    @GetMapping(WebRoutes.SESSION)
    ResponseEntity<WebAccount> session(Authentication caller) {
        String userId = caller.getName();
        String handle = accounts.handleOf(userId).orElseThrow(() -> new IllegalStateException(
                "the session names user " + userId + ", which has no account"));
        return ResponseEntity.ok(new WebAccount(userId, handle, handle));
    }

    /**
     * The caller's address, as far as a servlet can see it.
     *
     * <p>Read from the socket rather than from {@code X-Forwarded-For} here, and that is not what
     * makes it trustworthy: the container rewrites the socket address from that header anyway,
     * because {@code server.forward-headers-strategy} is set in {@code application.yml}. Whether the
     * header can be believed is the proxy's business — it has to overwrite the value rather than
     * append to it — and that precondition, and why it is the difference between a real address and
     * a caller-chosen one, is written out there.
     */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * @param phone         possibly null, when the body omitted it
     * @param captchaId     the challenge this request claims to have solved
     * @param captchaAnswer what the caller read in the image
     */
    record CodeRequest(String phone, String captchaId, String captchaAnswer) {
    }

    /** @param code the SMS code; see {@link RegisterAccountUseCase.Request} for what spends it */
    record RegistrationRequest(String phone, String code, String password, String username) {
    }

    record LoginRequest(String phone, String password) {
    }

    record ResetRequest(String phone, String code, String password) {
    }
}
