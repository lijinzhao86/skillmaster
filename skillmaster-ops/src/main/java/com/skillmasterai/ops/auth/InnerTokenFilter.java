package com.skillmasterai.ops.auth;

import com.skillmasterai.ops.config.OpsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The only credential ops accepts: {@code X-Inner-Token}, compared without an early exit.
 *
 * <p>Blank token means the trigger is off, and the answer is 404 rather than 401 — the same
 * choice the server's old trigger made, and for the same reason: an unconfigured endpoint
 * should not advertise that it exists.
 *
 * <p>The comparison is constant-time because a byte-by-byte compare leaks the prefix, and the
 * token is the only thing between CI and a full import run.
 */
@Component
public class InnerTokenFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Inner-Token";

    private final OpsProperties properties;

    public InnerTokenFilter(OpsProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/inner/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String expected = properties.innerToken();
        if (expected == null || expected.isBlank()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String presented = request.getHeader(HEADER);
        if (presented == null || !matches(expected, presented)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":{\"code\":\"unauthorized\"}}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean matches(String expected, String presented) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
