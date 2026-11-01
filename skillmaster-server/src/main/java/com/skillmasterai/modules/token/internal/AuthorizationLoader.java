package com.skillmasterai.modules.token.internal;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.token.TokenPolicy;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/** The reads {@link TokenStore} makes: an authorization row and the tokens filed under it. */
final class AuthorizationLoader {

    private final JdbcClient jdbc;
    private final RegisteredClientRepository clients;
    private final TokenPolicy policy;
    private final AuthorizationRowStore rows;

    AuthorizationLoader(JdbcClient jdbc, RegisteredClientRepository clients, TokenPolicy policy,
            AuthorizationRowStore rows) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.policy = policy;
        this.rows = rows;
    }

    OAuth2Authorization load(String authorizationId, String presentedValue, String presentedHash) {
        TokenRowMapper.AuthorizationRow row = jdbc.sql("SELECT id, registered_client_id, principal_name, grant_type,"
                        + " authorized_scopes, attributes, created_at, revoked_at"
                        + " FROM oauth_authorization WHERE id = :id")
                .param("id", authorizationId)
                .query((rs, rowNum) -> new TokenRowMapper.AuthorizationRow(
                        rs.getString("id"),
                        rs.getString("registered_client_id"),
                        rs.getString("principal_name"),
                        rs.getString("grant_type"),
                        rs.getString("authorized_scopes"),
                        rs.getString("attributes"),
                        rs.getString("created_at"),
                        rs.getString("revoked_at")))
                .optional()
                .orElse(null);
        if (row == null) {
            return null;
        }

        RegisteredClient client = clients.findById(row.registeredClientId());
        if (client == null) {
            throw new IllegalStateException("authorization " + row.id() + " names client '"
                    + row.registeredClientId() + "', which oauth_client does not have");
        }

        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(client)
                .id(row.id())
                .principalName(row.principalName())
                .authorizationGrantType(new AuthorizationGrantType(row.grantType()))
                .authorizedScopes(TokenRowMapper.scopesOf(row.authorizedScopes()))
                .attributes(attributes -> attributes.putAll(
                        TokenRowMapper.readAttributes(row.attributes())));

        codeToken(builder, row.id(), row.revoked(), presentedValue, presentedHash);
        accessToken(builder, row.id(), row.revoked(), presentedValue, presentedHash);
        refreshToken(builder, row.id(), row.createdAt(), row.revoked(), presentedValue, presentedHash);

        return builder.build();
    }

    private void codeToken(OAuth2Authorization.Builder builder, String authorizationId,
            boolean authorizationRevoked, String presentedValue, String presentedHash) {
        Optional<TokenRowMapper.CodeRow> row = selectToken(
                "SELECT code_hash AS hash, expires_at, used_at FROM auth_code",
                "code_hash", "expires_at DESC", authorizationId, presentedHash,
                (rs, n) -> new TokenRowMapper.CodeRow(
                        rs.getString("hash"), rs.getString("expires_at"), rs.getString("used_at")));
        row.ifPresent(code -> builder.token(
                new OAuth2AuthorizationCode(
                        TokenRowMapper.value(code.hash(), presentedValue, presentedHash),
                        // The code has no issued-at column of its own; the authorization's creation is
                        // the same instant, and nothing reads it back except equality on the value.
                        Timestamps.parse(authorizationCreatedAt(authorizationId)),
                        Timestamps.parse(code.expiresAt())),
                metadata -> TokenRowMapper.invalidated(metadata,
                        authorizationRevoked || code.usedAt() != null)));
    }

    private void accessToken(OAuth2Authorization.Builder builder, String authorizationId,
            boolean authorizationRevoked, String presentedValue, String presentedHash) {
        Optional<TokenRowMapper.AccessRow> row = selectToken("SELECT token_hash AS hash, scope, expires_at,"
                        + " revoked_at, created_at FROM access_token",
                "token_hash", "(revoked_at IS NULL) DESC, created_at DESC", authorizationId, presentedHash,
                (rs, n) -> new TokenRowMapper.AccessRow(rs.getString("hash"), rs.getString("scope"),
                        rs.getString("expires_at"), rs.getString("revoked_at"), rs.getString("created_at")));
        row.ifPresent(access -> builder.token(
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                        TokenRowMapper.value(access.hash(), presentedValue, presentedHash),
                        Timestamps.parse(access.createdAt()),
                        Timestamps.parse(access.expiresAt()),
                        TokenRowMapper.scopesOf(access.scope())),
                metadata -> TokenRowMapper.invalidated(metadata,
                        authorizationRevoked || access.revokedAt() != null)));
    }

    /**
     * Puts the refresh token back, with ADR 0024's two rules applied to what the framework sees.
     *
     * <p>Both are enforced here rather than in {@link TokenStore#save} because both are about what a
     * caller is *allowed to do next*, and the framework decides that from the object it gets back — a
     * token it sees as invalidated never reaches the point where it would issue a successor.
     *
     * <ul>
     *   <li><strong>The absolute ceiling</strong> is anchored on {@code oauth_authorization.created_at},
     *       not on the token: it is a limit on the whole authorization, and a token that outlives its
     *       authorization's ceiling by being rotated is exactly what it exists to stop. This is also
     *       the answer to the question ADR 0024 left open — "窗口从哪个时间戳起算" — for the ceiling
     *       half: the authorization's own creation, which is the only instant that survives every
     *       rotation.
     *   <li><strong>The grace window</strong> is anchored on the presented token's own
     *       {@code revoked_at}, which is the moment it was rotated away — the other half of that
     *       question, and the only timestamp that means "when this stopped being current". A token
     *       revoked inside the window is handed back **as if it were still live**, so the framework
     *       rotates again and the process that lost the race gets a working pair instead of a
     *       logout. Outside it, the token stays invalid and the chain comes down (ADR 0024).
     * </ul>
     *
     * <p>Only a token that was actually presented gets the grace, and only when it is the one whose
     * hash matched: the window is about a client re-presenting what it holds, not about a general
     * amnesty for revoked rows. That is also why the replay's consequence is decided here: this is the
     * only place that knows both which token was presented and which authorization it belongs to.
     *
     * <p><strong>The window needs one more condition, and leaving it out resurrects logged-out
     * credentials.</strong> The grace is for a race, and every race leaves a live successor behind —
     * that is what the other process is holding. A family with nothing live in it was ended on
     * purpose: {@code POST /oauth/revoke}, a logout, {@code revokeAllFor}. So the window applies only
     * while something in the family is still live, and outside that a revoked token is simply
     * revoked. Without this, a client that logged out and then retried would be handed a working pair
     * for the next sixty seconds, which is the opposite of what logging out means.
     *
     * <p><strong>The replay's consequence is a write, and the framework will not do it.</strong>
     * {@code OAuth2RefreshTokenAuthenticationProvider} does one thing to a refresh token that is not
     * active — {@code throw invalid_grant} — and nothing else: no revocation, no chain. So "窗口外撤销
     * 整条链" has to happen on this side of the interface, and it has to happen before the provider
     * gives up, because the provider never calls back afterwards. Revoking here rather than in an
     * exception handler keeps the rule in the module that owns the tables, which is where ADR 0023 put
     * it.
     *
     * <p>Deliberately narrow: a token whose authorization is past its ceiling is refused but does not
     * cascade. That authorization is over either way, its access token dies within the hour, and the
     * ceiling is evaluated on every load — so cascading there would write on every plain lookup.
     */
    private void refreshToken(OAuth2Authorization.Builder builder, String authorizationId,
            String authorizationCreatedAt, boolean authorizationRevoked, String presentedValue,
            String presentedHash) {
        Optional<TokenRowMapper.RefreshRow> row = selectToken("SELECT token_hash AS hash, expires_at, revoked_at,"
                        + " created_at FROM refresh_token",
                "token_hash", "(revoked_at IS NULL) DESC, created_at DESC", authorizationId, presentedHash,
                (rs, n) -> new TokenRowMapper.RefreshRow(rs.getString("hash"), rs.getString("expires_at"),
                        rs.getString("revoked_at"), rs.getString("created_at")));
        if (row.isEmpty()) {
            return;
        }
        TokenRowMapper.RefreshRow refresh = row.get();

        boolean pastCeiling = pastAbsoluteCeiling(authorizationCreatedAt);
        boolean presented = presentedHash != null && refresh.hash().equals(presentedHash);
        // **The authorization's own row decides this, and it has to come first.** `familyAlive` asking
        // only the token rows is what made an ended authorization resumable: a row that escaped the
        // revocation's sweep reads as a live successor, so the family looks alive, so the grace window
        // forgives a spent token and hands out a fresh pair (V9).
        boolean familyAlive = !authorizationRevoked && rows.liveRefreshHash(authorizationId) != null;
        boolean forgiven = !pastCeiling && presented && familyAlive
                && withinReplayGrace(refresh.revokedAt());
        boolean replayed = !pastCeiling && presented && familyAlive && !forgiven
                && refresh.revokedAt() != null;

        builder.token(
                new OAuth2RefreshToken(
                        TokenRowMapper.value(refresh.hash(), presentedValue, presentedHash),
                        Timestamps.parse(refresh.createdAt()),
                        Timestamps.parse(refresh.expiresAt())),
                metadata -> {
                    if (authorizationRevoked || pastCeiling) {
                        // Marked directly rather than through the row's own timestamp: the row may have
                        // escaped the sweep and have nothing to read.
                        TokenRowMapper.invalidated(metadata, true);
                    } else if (!forgiven) {
                        TokenRowMapper.invalidated(metadata, refresh.revokedAt());
                    }
                });

        if (replayed) {
            rows.revokeAuthorization(authorizationId, Timestamps.now(), "refresh_token_replayed");
        }
    }

    /** Whether the authorization has outlived its ceiling, whatever state its tokens are in. */
    private boolean pastAbsoluteCeiling(String authorizationCreatedAt) {
        return Timestamps.parse(authorizationCreatedAt)
                .plus(policy.refreshTokenAbsolute())
                .isBefore(Instant.now());
    }

    /** Whether a token revoked at this instant is still inside the window that forgives a race. */
    private boolean withinReplayGrace(String revokedAt) {
        if (revokedAt == null) {
            return false;
        }
        return !Timestamps.parse(revokedAt).plus(policy.refreshReplayGrace()).isBefore(Instant.now());
    }

    /**
     * A token row, preferring the one whose hash was presented.
     *
     * <p>With a presented hash there is exactly one candidate and the choice does not arise. Without
     * one — {@link TokenStore#findById} — a decision has to be made, and the framework's model holds
     * one access token where our table holds every one this authorization ever issued. Live first,
     * then newest: the answer the caller is asking for is "the current state of this authorization",
     * not "the oldest token it ever had".
     */
    private <T> Optional<T> selectToken(String select, String hashColumn, String orderBy,
            String authorizationId, String presentedHash, RowMapper<T> mapper) {
        // Table and column names are literals chosen by the caller, never input.
        String sql = presentedHash == null
                ? select + " WHERE authorization_id = :id ORDER BY " + orderBy + " LIMIT 1"
                : select + " WHERE authorization_id = :id AND " + hashColumn + " = :hash";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("id", authorizationId);
        if (presentedHash != null) {
            spec = spec.param("hash", presentedHash);
        }
        return spec.query(mapper).optional();
    }

    private String authorizationCreatedAt(String authorizationId) {
        return jdbc.sql("SELECT created_at FROM oauth_authorization WHERE id = :id")
                .param("id", authorizationId)
                .query(String.class)
                .single();
    }
}
