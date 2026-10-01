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

    /** The image a caller has to read before either of the two code-sending endpoints will listen. */
    static final String CAPTCHA = "/captcha";

    static final String LOGIN = "/login";
    static final String LOGOUT = "/logout";
    static final String REGISTER = "/register";
    static final String REGISTER_CODE = "/register/code";
    static final String RESET = "/reset";
    static final String RESET_CODE = "/reset/code";
}
