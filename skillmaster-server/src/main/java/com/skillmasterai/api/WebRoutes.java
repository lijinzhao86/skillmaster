package com.skillmasterai.api;

/**
 * §4.1's browser plane: the paths, in one place because the controller maps them and the security
 * configuration has to permit them.
 *
 * <p><strong>That second one cannot use these constants.</strong> The architecture rule forbids
 * {@code config} from depending on {@code api}, so {@code SecurityConfig} names what it needs by
 * area instead: {@code GET /web/session} authenticated, {@code /web/**} permitted. So renaming a
 * path here does *not* mean looking there too — the rule is a prefix and a single read path, and
 * the write paths are covered by {@code /web/**}. The one thing that would need both sides changed
 * is moving the authenticated read off {@code /web/session}, or moving a write out from under
 * {@code /web/}.
 */
final class WebRoutes {

    private WebRoutes() {
    }

    static final String BASE = "/web";

    /** The one authenticated read: who is signed in, and it is also how a client bootstraps CSRF. */
    static final String SESSION = "/session";

    /**
     * The publish queue (v18): every draft waiting on a skill the caller may publish from.
     *
     * <p>Named here as well as in {@code SecurityConfig} — that class cannot use this constant (the
     * architecture rule keeps {@code config} off {@code api}), and this read must not fall to the
     * {@code /web/**} permitAll: every answer on it is about one person's own drafts.
     */
    static final String PUBLISH_QUEUE = "/publish-queue";

    /**
     * Publishing several drafts in one request (v18). Under {@code /web/skills}, so the
     * {@code /web/skills/**} authentication rule already covers it; the CSRF filter covers the
     * write, as it does every write on this plane.
     */
    static final String PUBLISH_BATCH = "/skills/publish-batch";

    /** The image a caller has to read before either of the two code-sending endpoints will listen. */
    static final String CAPTCHA = "/captcha";

    static final String LOGIN = "/login";
    static final String LOGOUT = "/logout";
    static final String REGISTER = "/register";
    static final String REGISTER_CODE = "/register/code";
    static final String RESET = "/reset";
    static final String RESET_CODE = "/reset/code";

    /** Whether a candidate username is free. A read, so no CSRF header, and it needs no session. */
    static final String USERNAME_AVAILABILITY = "/username/availability";

    /**
     * The caller's own credentials (ADR 0038): the screen where a key is issued, listed, revoked and
     * renewed.
     *
     * <p><strong>Two addresses, and this constant is only one of them.</strong> The API plane is
     * {@code /web/keys} (this constant under {@code BASE}); the console's *page* is
     * {@code /account/keys}, which no controller here owns — it is the SPA's route, and it is what the
     * CLI's "your key expired, here is where to renew it" points at. That page address is a contract
     * too, and a message pointing at a moving one stops working quietly, but moving it is a change in
     * the web application and in the strings that name it, not in this class.
     */
    static final String KEYS = "/keys";

    /** One key, addressed by id: the rotate and revoke writes name it in the path. */
    static final String KEY_BY_ID = KEYS + "/{id}";

    /**
     * The caller's own logins (ADR 0043): the screen where a device is seen and ended.
     *
     * <p>Two addresses under it, and the shape is the same as the key screen's: the collection for
     * the read, and a member for the write — except that the member is named by a
     * {@code device_id} rather than by a key id, and "every device but this one" is a second write
     * beside the member rather than a loop over the first.
     */
    static final String SESSIONS = "/sessions";

    /**
     * One device, addressed by its {@code device_id}.
     *
     * <p><strong>Never by a session id.</strong> That id is worth as much as the cookie carrying it,
     * so it is not in the list, not in this path and not in a response body — the console names a row
     * by a ULID this service minted for the purpose. See {@code SessionDevices}.
     */
    static final String SESSION_BY_ID = SESSIONS + "/{id}";

    /**
     * The CLI's login (v4): the browser action that mints, the public exchange that delivers, and the
     * console write that ends one.
     *
     * <p><strong>One of the three is deliberately not under {@code BASE}.</strong> A CLI has no
     * session, which is why the exchange is asked for at its own address and given a limit of its own
     * — being on this plane would mean either an authenticated endpoint the CLI cannot call, or a
     * blanket {@code permitAll} over {@code /web/**} that would unauthenticate everything else on it.
     *
     * <p><strong>And the screen's own address is not here at all.</strong> {@code /cli-sign-in} is the
     * SPA's route, in the same sense {@code /account/keys} is: no controller owns it, and the server's
     * only part in it is the {@code return_to} redirect {@code TokenSecurityConfig} builds when nobody
     * is signed in. Putting it here would suggest this class can move it, and moving it would be a
     * change in the web application, not in the API.
     */
    static final String CLI_SIGN_INS = "/cli-sign-ins";

    /** One sign-in, addressed by the authorization it names: the console ends it through this. */
    static final String CLI_SIGN_IN_BY_ID = CLI_SIGN_INS + "/{id}";

    /**
     * Redeeming a one-time code for the credential it carries.
     *
     * <p>Absolute rather than relative to {@code BASE}, and that is the whole of its security story:
     * it is the one write on this server a caller with no session may make. What stands in for a
     * session is the code — single-use, minutes long, and bound to a PKCE challenge the caller has to
     * prove it holds.
     */
    static final String CLI_SIGN_IN_EXCHANGE = CLI_SIGN_INS + "/exchange";

    /**
     * The screen's other button: no login happens, and the browser is sent back to where it came from.
     *
     * <p><strong>Why this cannot be done in the page.</strong> The refusal has to end at the CLI's
     * loopback address, and the page does not have it — the registered redirect URI is read from the
     * client row on the server, deliberately, because a target chosen by the request is an open
     * redirect. So the "no" comes back here to ask the way, and the answer comes from the same row the
     * mint's answer does.
     */
    static final String CLI_SIGN_IN_REFUSE = CLI_SIGN_INS + "/refuse";

    /**
     * Whether a registration code send would be asked for a captcha.
     *
     * <p>A read under a write path, which reads oddly and is the honest address for it: the question
     * is about one thing the client is about to do, and asking it must not do that thing.
     */
    static final String REGISTER_CODE_CAPTCHA = "/register/code/captcha-required";
}
