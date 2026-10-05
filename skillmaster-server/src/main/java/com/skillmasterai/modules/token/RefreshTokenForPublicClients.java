package com.skillmasterai.modules.token;

import java.time.Instant;
import java.util.Base64;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Issues refresh tokens the framework declines to issue, and nothing else.
 *
 * <p>{@code OAuth2RefreshTokenGenerator} returns {@code null} — silently — for a public client on the
 * authorization_code grant (its private {@code isPublicClientForAuthorizationCodeGrant}). Our CLI is
 * exactly that shape: PKCE, no secret, a native app. Left alone, the CLI never receives a refresh
 * token, and "one consent, then silent refresh for months" has nothing to stand on.
 *
 * <p><strong>Why supplying it is right rather than a workaround.</strong> The guard protects against
 * a browser app keeping a long-lived bearer credential in JavaScript-reachable storage. A native app
 * is not that case, and the standards say so: RFC 8252 gives it the loopback redirect, and RFC 9700
 * §2.2.2 permits a public client's refresh token precisely when it is rotated. We rotate. The guard
 * is a blanket rule for a shape we are not (ADR 0026).
 *
 * <p>Generation is byte-for-byte the framework's — the same 96-byte base64url key — so nothing about
 * the tokens themselves differs from what the framework would have produced.
 */
public final class RefreshTokenForPublicClients implements OAuth2TokenGenerator<OAuth2RefreshToken> {

    private final StringKeyGenerator keys =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
            return null;
        }
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(
                context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
        return new OAuth2RefreshToken(keys.generateKey(), issuedAt, expiresAt);
    }
}
