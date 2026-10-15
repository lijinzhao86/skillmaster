package com.skillmasterai.modules.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Derives the request's security context from its bearer token.
 *
 * <p>This is the seam Spring Security provides for "where does the context come from", and it is
 * used here rather than a custom filter that authenticates on its own. That alternative was
 * tried and did not work: a filter placed immediately after {@code SecurityContextHolderFilter}
 * could read the token, validate it, and see the resulting authentication in
 * {@code SecurityContextHolder} — both before and after calling down the chain — while the
 * authorization filter downstream still treated the request as anonymous and answered 401. The
 * cause was not isolated (the context is deferred, and neither mutating it nor replacing it via
 * {@code setContext} propagated), and finding it was not worth more time once this approach was
 * confirmed working. Recorded because the failure is invisible from inside such a filter and
 * will look like a validator bug to whoever tries it next.
 *
 * <p>Letting the framework resolve the context removes the whole question: there is no filter
 * ordering to get right, and the context is installed by the filter that owns it.
 *
 * <p>Stateless by construction: nothing is stored, so {@link #saveContext} does nothing. Every
 * request carries its own token, which is also why the chain needs no CSRF token — there is no
 * ambient authority for a cross-site request to borrow.
 */
public final class BearerTokenSecurityContextRepository implements SecurityContextRepository {

    /**
     * Where a refused credential's reason waits for the 401 handler.
     *
     * <p><strong>Both ends of this are in this class's module, and that is the whole reason it is a
     * request attribute rather than something richer.</strong> The reason can only be known by the
     * validator (M2), and the response is written by the entry point (M3) — so if M3 had to ask M2 for
     * it, M2 and M3 would depend on each other and {@code ArchitectureTest} would refuse the build.
     * The repository is M3's, it already holds the request, and it already has the answer in hand: a
     * request attribute is the one channel that crosses no module boundary at all.
     *
     * <p>Nothing reads it unless a 401 is being written, and the attribute dies with the request.
     */
    static final String REJECTION_ATTRIBUTE =
            BearerTokenSecurityContextRepository.class.getName() + ".rejection";

    private final TokenValidator validator;
    private final SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();

    public BearerTokenSecurityContextRepository(TokenValidator validator) {
        this.validator = validator;
    }

    @Override
    public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
        return contextFor(requestResponseHolder.getRequest());
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request,
            HttpServletResponse response) {
        // Nothing to remember between requests.
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return BearerToken.presented(request);
    }

    private SecurityContext contextFor(HttpServletRequest request) {
        String token = BearerToken.from(request);
        if (token == null) {
            return strategy.createEmptyContext();
        }
        return switch (validator.validate(token, callerAddress(request))) {
            case TokenValidation.Accepted accepted -> {
                SecurityContext context = strategy.createEmptyContext();
                context.setAuthentication(new BearerAuthentication(accepted.subject()));
                yield context;
            }
            case TokenValidation.Rejected rejected -> {
                // Recorded for the entry point, which runs later in the chain and has no way to ask
                // the validator again without paying for a second lookup on every 401. The reason is
                // one of four codes; TokenValidation refuses to carry anything else.
                request.setAttribute(REJECTION_ATTRIBUTE, rejected.reason());
                yield strategy.createEmptyContext();
            }
        };
    }

    /**
     * The caller's address as far as a servlet can see it.
     *
     * <p>Read from the socket rather than from {@code X-Forwarded-For} <em>here</em>, which is not
     * what makes it trustworthy: the container rewrites the socket address from that header anyway,
     * because {@code server.forward-headers-strategy} is set (ADR 0018). Whether the header can be
     * believed is the proxy's business — it has to overwrite the value rather than append to it. What
     * this method must not do is form a second opinion about it, because two readings of one header
     * is how a signal ends up disagreeing with the throttle that shares it.
     */
    private static String callerAddress(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
