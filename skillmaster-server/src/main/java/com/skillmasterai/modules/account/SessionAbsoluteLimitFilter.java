package com.skillmasterai.modules.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session that has reached the absolute ceiling — {@link SessionPolicy#absolute()} — on the
 * request that finds it there.
 *
 * <p><strong>Read-time enforcement is what makes the ceiling true.</strong> Nothing else in the stack
 * can express it: Spring Session's {@code expiry_time} is "last access plus the idle window", so a
 * session that is touched constantly never expires there, and a cleanup task would leave a session
 * over the ceiling usable between two sweeps. The row is the framework's, but the rule is ours, so
 * the rule runs where every request passes (ADR 0043 §决定 1).
 *
 * <p><strong>{@code invalidate()} and not "refuse this request".</strong> Spring Session's wrapper
 * calls {@code SessionRepository.deleteById} on the spot, so the row is gone before the next request
 * rather than at the end of some sweep — and a client that keeps sending the cookie finds nothing
 * behind it. That is the same shape accepting #4's revocation has, and it is deliberate that the two
 * cannot differ: both answer "this login is over" by removing the row.
 *
 * <p><strong>It runs before any security context is loaded, and it runs for every request.</strong>
 * The context is read from the session by {@code SecurityContextHolderFilter}; one already loaded
 * would carry on authenticated and an expired session would get a successful answer. So this sits
 * immediately after Spring Session wraps the request and before every security chain — registered
 * once, as a servlet filter, rather than added to a chain. It was added to the browser chain first,
 * and that chain was correct while the authorization server's was not: a session eight days old was
 * refused by {@code /web/**} and still authenticated at {@code /oauth/authorize}, which is where a
 * browser mints a CLI credential. See {@code config.SessionConfig} for the order and the measurement.
 *
 * <p>Being once per request also means it is asked about requests that carry no session — the API
 * plane, discovery, static paths — where it does nothing at all: {@code getSession(false)} returns
 * null and the chain is invoked.
 *
 * <p><strong>But a request that carries a cookie and was not going to read the session now does, and
 * that is a change on the API plane.</strong> {@code getSession(false)} loads the row and stamps it
 * as accessed, so a request to {@code /api/v1/**} from a browser holding a session cookie slides the
 * 24-hour idle ruler, where before this filter it would have been left alone — and because the load
 * marks the session changed, the commit writes {@code last_access_time} back. It is left as it is:
 * a person whose browser makes a request is using their session, and the alternative — a ceiling
 * rule that only applies where somebody remembered to read the session — is the defect this filter
 * exists to undo. The cost is one row load and, at most, one update per request that arrives with a
 * cookie, including on the plane that does not need one.
 *
 * <p><strong>The session is the only thing it touches, and it is idempotent.</strong> Once the row
 * is gone there is nothing to find, so the request that follows this one does no work here — the
 * stale cookie is the client's to drop.
 */
public final class SessionAbsoluteLimitFilter extends OncePerRequestFilter {

    private final SessionPolicy policy;

    public SessionAbsoluteLimitFilter(SessionPolicy policy) {
        this.policy = policy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            // `getCreationTime` is `spring_session.creation_time`, which the framework wrote at the
            // login and has not moved since — the whole difference between this ruler and the idle
            // one, which the framework reads out of `last_access_time`.
            Instant created = Instant.ofEpochMilli(session.getCreationTime());
            if (policy.pastAbsoluteCeiling(created, Instant.now())) {
                session.invalidate();
            }
        }
        chain.doFilter(request, response);
    }
}
