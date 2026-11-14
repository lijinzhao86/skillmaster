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

    /**
     * Deletes the signed-in account (v56, ADR 0175).
     *
     * <p><strong>Console only, by decision.</strong> ADR 0081 allows a high-risk operation to have
     * no CLI counterpart when the product owner says so; irreversible account deletion is that
     * exception, and the password prompt plus the confirmation dialog are why.
     */
    static final String DELETE_ACCOUNT = "/account/delete";

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
     * Service credentials (v52): the console's half of the vault.
     *
     * <p>**Two addresses again, and the pair matters more here than on the key screen.** The API
     * plane carries the same collection for the CLI; this one exists because a browser session is a
     * different credential (ADR 0040) and because **writing a share has no API-plane route at all**
     * (ADR 0081's first exception) — `/web/secrets/{id}/shares` is the only place a grant is
     * created. The console's page is `/account/secrets`, which is the SPA's route and the string
     * `skmr secret share` prints, so moving either one moves both.
     */
    static final String SECRETS = "/secrets";

    /** One credential: value, quota, its bindings and its shares all hang off this. */
    static final String SECRET_BY_ID = SECRETS + "/{id}";

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
     * The console's list of CLI logins, and the write that ends one.
     *
     * <p><strong>The screen's own address is not here at all.</strong> {@code /cli-sign-in} is the
     * SPA's route, in the same sense {@code /account/keys} is: no controller owns it, and the server's
     * only part in it is the {@code return_to} redirect {@code TokenSecurityConfig} builds when nobody
     * is signed in. Putting it here would suggest this class can move it, and moving it would be a
     * change in the web application, not in the API.
     */
    static final String CLI_SIGN_INS = "/cli-sign-ins";

    /** One sign-in, addressed by the authorization it names: the console ends it through this. */
    static final String CLI_SIGN_IN_BY_ID = CLI_SIGN_INS + "/{id}";

    /**
     * The CLI's question (v68): anonymous creation, anonymous view, anonymous poll.
     *
     * <p><strong>All three are absolute rather than under {@code BASE}.</strong> The caller is a
     * process on the person's machine with no session; being on the browser plane would mean either
     * an authenticated endpoint it cannot call, or a blanket {@code permitAll} over {@code /web/**}
     * that would unauthenticate everything else on it. The poll carries a limit of its own keyed on
     * the address.
     */
    static final String CLI_DEVICE_SIGN_INS = "/cli-device-sign-ins";

    /** One question, addressed by its ticket: the page reads it, the CLI polls it. */
    static final String CLI_DEVICE_SIGN_IN = CLI_DEVICE_SIGN_INS + "/{ticket}";

    /** The one poll, which may hand over the credential. */
    static final String CLI_DEVICE_SIGN_IN_TOKEN = CLI_DEVICE_SIGN_IN + "/token";

    /**
     * The waiting process giving up (Ctrl-C), so the link stops being a question.
     *
     * <p>Anonymous like the poll, and for the same reason: what proves it is the device secret, which
     * stayed with the process that asked.
     */
    static final String CLI_DEVICE_SIGN_IN_CANCEL = CLI_DEVICE_SIGN_IN + "/cancel";

    /**
     * The browser's two answers to a question, on the session plane.
     *
     * <p>The approval writes the authorization row and nothing else; the refusal writes nothing. Both
     * take the person from the session rather than from the body, the way every other write on this
     * plane does.
     */
    static final String CLI_DEVICE_APPROVALS = "/cli-device-sign-ins";

    /** The yes: a person, in a session, agreeing to the question. */
    static final String CLI_DEVICE_APPROVE = CLI_DEVICE_APPROVALS + "/{ticket}/approve";

    /** The no: the same person closing the question without granting anything. */
    static final String CLI_DEVICE_REFUSE = CLI_DEVICE_APPROVALS + "/{ticket}/refuse";

    /**
     * Whether a registration code send would be asked for a captcha.
     *
     * <p>A read under a write path, which reads oddly and is the honest address for it: the question
     * is about one thing the client is about to do, and asking it must not do that thing.
     */
    static final String REGISTER_CODE_CAPTCHA = "/register/code/captcha-required";
}
