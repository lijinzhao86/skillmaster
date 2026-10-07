package com.skillmasterai.modules.auth;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/**
 * The browser plane's {@code 401}: the same envelope as every other error, and deliberately no
 * {@code WWW-Authenticate}.
 *
 * <p>That header is what makes the bearer plane's 401 useful — it names the metadata a client must
 * read before it can authenticate — and it is also an instruction. A client that sends a session
 * cookie and is answered with {@code WWW-Authenticate: Bearer} has been told to go and obtain a
 * token it never wanted, so on this plane the header is not sent at all.
 *
 * <p><strong>There is no CSRF case here, although the design expected one.</strong> A rejected CSRF
 * token arrives at an entry point only if it is routed as an {@code AccessDeniedException} by
 * {@code ExceptionTranslationFilter}, which would answer 401 to an anonymous request — a false
 * statement about a request that carried no credentials to reject. It does not: {@code CsrfFilter}
 * holds its own {@code AccessDeniedHandler} and calls it directly, so a CSRF failure is a 403 on
 * every request regardless of who sent it.
 */
public final class WebAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public WebAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(response.getOutputStream(), ApiError.of(
                ErrorCode.UNAUTHENTICATED, "Authentication is required."));
    }
}
