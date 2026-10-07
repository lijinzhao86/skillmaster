package com.skillmasterai.api;

import com.skillmasterai.modules.auth.WebAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/**
 * How a browser session starts and ends on this plane.
 *
 * <p>Both operations are a few lines that are easy to get subtly wrong, and wrong in ways that look
 * like something else. Written once here rather than inlined at each endpoint.
 */
final class WebSession {

    private WebSession() {
    }

    /**
     * Signs a user in: a new session id, the security context stored under it, and a new CSRF token.
     *
     * <p><strong>The id rotation is the session-fixation defence, and nothing else performs it.</strong>
     * Spring Security's own change-session-id strategy runs inside its authentication filters; this
     * login happens in a controller, so no filter sees it and no strategy fires. Without the
     * rotation, a session id an attacker planted before the login stays valid after it — which is
     * the whole attack.
     *
     * <p><strong>The explicit {@code saveContext} is the other half.</strong> Since Spring Security
     * 6 the holder only <em>loads</em> from the repository; nothing saves on the way out. Without
     * this line the endpoint answers 200 and the next request is anonymous, which reads like a
     * client that forgot its cookie.
     *
     * <p>The CSRF token is replaced for the same reason the session id is — the value the client
     * arrived with may be one an attacker chose. <strong>Not by saving a null token:</strong> that
     * removes the cookie outright, leaving the client with nothing to send, so the first write after
     * a successful login would be refused.
     */
    static void establish(HttpServletRequest request, HttpServletResponse response,
            SecurityContextRepository contexts, CsrfTokenRepository csrf, String userId) {
        // Created first so there is something to rotate: changeSessionId requires an existing one.
        request.getSession(true);
        request.changeSessionId();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new WebAuthentication(userId));
        SecurityContextHolder.setContext(context);

        contexts.saveContext(context, request, response);
        csrf.saveToken(freshToken(csrf, request, response), request, response);
    }

    /**
     * Ends the session, if there was one. Idempotent: logging out twice is not an error.
     *
     * <p>The CSRF cookie is deliberately left in place. It is not a credential — it authorizes
     * nothing on its own, and it is only ever compared against itself — so there is nothing to
     * revoke, and deleting it would leave the client unable to post until it made another round trip
     * to be issued one. It is replaced at the next login.
     */
    static void clear(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    /**
     * A token of the same shape as the current one, with a value nobody has seen.
     *
     * <p>Built from the current token's header and parameter names rather than from constants here,
     * so that a repository configured with different names cannot end up with a cookie the filter
     * will not look for.
     */
    private static CsrfToken freshToken(CsrfTokenRepository csrf, HttpServletRequest request,
            HttpServletResponse response) {
        CsrfToken current = csrf.loadDeferredToken(request, response).get();
        return new DefaultCsrfToken(current.getHeaderName(), current.getParameterName(),
                UUID.randomUUID().toString());
    }
}
