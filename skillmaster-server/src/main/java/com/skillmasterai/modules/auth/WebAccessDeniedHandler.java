package com.skillmasterai.modules.auth;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.ObjectMapper;

/**
 * The browser plane's {@code 403} — §4.1's {@code forbidden}.
 *
 * <p>Its one source is CSRF. There are no authorities on this plane to lack: every endpoint is
 * either public or open to anyone signed in, so a request is never refused for <em>who</em> sent
 * it, and the generic "you may not do that" would be a misleading way to describe a missing token.
 * The message therefore names the recovery, which is one sentence and is exactly what the client
 * that hits this needs — see {@link WebAuthenticationEntryPoint} for why this, and not a 401, is
 * what a rejected CSRF token produces.
 */
public final class WebAccessDeniedHandler implements AccessDeniedHandler {

    private static final String MISSING_CSRF_TOKEN = "The CSRF token was missing or stale. Read it "
            + "from the session response and send it back in the X-XSRF-TOKEN header.";

    private final ObjectMapper objectMapper;

    public WebAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(response.getOutputStream(), ApiError.of(ErrorCode.FORBIDDEN,
                causedByCsrf(accessDeniedException)
                        ? MISSING_CSRF_TOKEN
                        : "The request was rejected."));
    }

    /** The failure is a {@link CsrfException}, possibly wrapped by whatever caught and rethrew it. */
    private static boolean causedByCsrf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CsrfException) {
                return true;
            }
        }
        return false;
    }
}
