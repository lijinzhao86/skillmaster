package com.skillmasterai.modules.auth;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code 401} response shape of §4.1:
 * {@code WWW-Authenticate: Bearer resource_metadata="<base>/.well-known/oauth-protected-resource"}.
 *
 * <p>The {@code resource_metadata} parameter is the whole point of the header — it is how a
 * client that has never spoken to us discovers where the authorization server is. It has to be
 * an absolute URL, which is why this class needs the configured public base rather than deriving
 * one from the request: behind a proxy the request's own idea of its host is not the client's.
 *
 * <p>A token that was presented and rejected additionally gets {@code error="invalid_token"}.
 * That is RFC 6750 and leaks nothing — it separates "you sent no credentials" from "what you
 * sent was not accepted", which is the difference between a client bug and a server misconfiguration.
 *
 * <p><strong>The body says more than the challenge, and that is deliberate.</strong> The challenge
 * is a protocol fact with one spelling and stays as it was; the reason a credential stopped working
 * is a fact for a person, and it goes in the envelope's {@code code} where a client already switches
 * on stable strings (§4.1). Splitting it this way means there is exactly one authority for each
 * answer rather than two that can disagree. Which reasons exist, and why only a caller holding a real
 * credential can learn one, is on {@link TokenValidation}.
 */
public final class BearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource";

    private final String resourceMetadataUrl;
    private final ObjectMapper objectMapper;

    public BearerAuthenticationEntryPoint(String publicBaseUrl, ObjectMapper objectMapper) {
        this.resourceMetadataUrl = withoutTrailingSlash(publicBaseUrl) + RESOURCE_METADATA_PATH;
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        boolean tokenWasPresented = BearerToken.presented(request);
        ErrorCode reason = reason(request, tokenWasPresented);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge(tokenWasPresented));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of(reason, message(reason, tokenWasPresented)));
    }

    /**
     * Why this request was refused — from the validator, when it had something to say.
     *
     * <p>The attribute is set by the repository that called the validator, and it is absent in every
     * other way a 401 happens: no credential at all, a credential that matches nothing, or an
     * endpoint reached with no token on a plane that needs one. All of those are
     * {@link ErrorCode#UNAUTHENTICATED}, which is exactly what this endpoint did before reasons
     * existed.
     */
    private static ErrorCode reason(HttpServletRequest request, boolean tokenWasPresented) {
        if (!tokenWasPresented) {
            return ErrorCode.UNAUTHENTICATED;
        }
        Object recorded = request.getAttribute(
                BearerTokenSecurityContextRepository.REJECTION_ATTRIBUTE);
        return recorded instanceof ErrorCode code ? code : ErrorCode.UNAUTHENTICATED;
    }

    private static String message(ErrorCode reason, boolean tokenWasPresented) {
        return switch (reason) {
            // **Credential-neutral, and v4 is what made that necessary.** This endpoint sees an
            // ErrorCode and nothing about the credential behind it, so the sentences used to say "API
            // key" — true while only keys produced these reasons. v4 gave OAuth access tokens the same
            // vocabulary, which turned those sentences into a false statement on the wire for a CLI
            // login. The sentences a person should act on are written by the client, which knows what
            // it is holding; these are the fallback for anything that is not our CLI.
            case TOKEN_REVOKED -> "This credential has been revoked.";
            case TOKEN_EXPIRED -> "This credential has expired.";
            case TOKEN_IDLE -> "This credential has not been used for too long.";
            case ACCOUNT_SUSPENDED -> "The account this credential belongs to is not active.";
            default -> tokenWasPresented
                    ? "The presented token was not accepted."
                    : "Authentication is required.";
        };
    }

    private String challenge(boolean tokenWasPresented) {
        StringBuilder challenge = new StringBuilder("Bearer");
        if (tokenWasPresented) {
            challenge.append(", error=\"invalid_token\"");
        }
        return challenge.append(", resource_metadata=\"").append(resourceMetadataUrl).append('"')
                .toString();
    }

    static String withoutTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
