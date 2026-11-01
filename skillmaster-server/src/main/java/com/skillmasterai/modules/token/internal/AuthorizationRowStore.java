package com.skillmasterai.modules.token.internal;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.token.TokenAudience;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;

/** The writes {@link TokenStore} makes: the authorization row and the three token tables under it. */
final class AuthorizationRowStore {

    private final JdbcClient jdbc;
    private final TokenAudience audience;
    private final AuditLog audit;

    AuthorizationRowStore(JdbcClient jdbc, TokenAudience audience, AuditLog audit) {
        this.jdbc = jdbc;
        this.audience = audience;
        this.audit = audit;
    }

    void upsertAuthorization(OAuth2Authorization authorization) {
        jdbc.sql("INSERT INTO oauth_authorization (id, registered_client_id, principal_name,"
                        + " grant_type, authorized_scopes, attributes, state, created_at)"
                        + " VALUES (:id, :clientId, :principal, :grantType, :scopes, :attributes,"
                        + " :state, :createdAt)"
                        + " ON CONFLICT (id) DO UPDATE SET authorized_scopes = EXCLUDED.authorized_scopes,"
                        + " grant_type = EXCLUDED.grant_type, attributes = EXCLUDED.attributes,"
                        // COALESCE because a later save may no longer carry the state, and losing it
                        // would strand an authorization mid-consent with no way to look it up.
                        + " state = COALESCE(EXCLUDED.state, oauth_authorization.state)")
                .param("id", authorization.getId())
                .param("clientId", authorization.getRegisteredClientId())
                .param("principal", authorization.getPrincipalName())
                .param("grantType", authorization.getAuthorizationGrantType().getValue())
                .param("scopes", String.join(" ", authorization.getAuthorizedScopes()))
                .param("attributes", TokenRowMapper.JSON.writeValueAsString(
                        TokenRowMapper.attributesToStore(authorization)))
                .param("state", TokenRowMapper.stateOf(authorization))
                // Preserved across updates by the conflict clause above not naming it.
                .param("createdAt", Timestamps.now())
                .update();
    }

    void upsertCode(OAuth2Authorization authorization,
            OAuth2Authorization.Token<OAuth2AuthorizationCode> code, Set<String> stored, String now) {
        // The three columns the framework never reads back but the design keeps — which redirect the
        // code was issued for, and the PKCE challenge it is bound to — live in the authorization
        // request inside `attributes`. Absent means the code cannot be verified at all, so this fails
        // rather than writing a row with empty values that would look fine.
        TokenRowMapper.OAuth2AuthorizationRequestFields request =
                TokenRowMapper.authorizationRequestFields(authorization);

        jdbc.sql("INSERT INTO auth_code (code_hash, client_id, user_id, redirect_uri, scope,"
                        + " code_challenge, method, resource, expires_at, used_at, authorization_id)"
                        + " VALUES (:hash, :clientId, :userId, :redirectUri, :scope, :challenge,"
                        + " :method, :resource, :expiresAt, :usedAt, :authorizationId)"
                        // COALESCE, not EXCLUDED: a spent code stays spent. The framework re-saves an
                        // authorization it read back, and a plain assignment would clear the flag.
                        + " ON CONFLICT (code_hash) DO UPDATE SET"
                        + " used_at = COALESCE(auth_code.used_at, EXCLUDED.used_at)")
                .param("hash", TokenRowMapper.toHash(code.getToken().getTokenValue(), stored))
                .param("clientId", authorization.getRegisteredClientId())
                .param("userId", authorization.getPrincipalName())
                .param("redirectUri", request.redirectUri())
                .param("scope", String.join(" ", authorization.getAuthorizedScopes()))
                .param("challenge", request.codeChallenge())
                .param("method", request.codeChallengeMethod())
                .param("resource", request.resource())
                .param("expiresAt", Timestamps.format(code.getToken().getExpiresAt()))
                .param("usedAt", code.isInvalidated() || authorizationRevoked(authorization.getId())
                        ? now : null)
                .param("authorizationId", authorization.getId())
                .update();
    }

    void upsertAccessToken(OAuth2Authorization authorization,
            OAuth2Authorization.Token<OAuth2AccessToken> access, Set<String> stored, String now) {
        OAuth2AccessToken token = access.getToken();
        String hash = TokenRowMapper.toHash(token.getTokenValue(), stored);
        String subject = authorization.getPrincipalName();

        jdbc.sql("INSERT INTO access_token (token_hash, client_id, user_id, scope, audience,"
                        + " expires_at, revoked_at, created_at, authorization_id)"
                        + " VALUES (:hash, :clientId, :userId, :scope, :audience, :expiresAt,"
                        + " :revokedAt, :createdAt, :authorizationId)"
                        + " ON CONFLICT (token_hash) DO UPDATE SET"
                        + " revoked_at = COALESCE(access_token.revoked_at, EXCLUDED.revoked_at)")
                .param("hash", hash)
                .param("clientId", authorization.getRegisteredClientId())
                .param("userId", subject)
                .param("scope", String.join(" ", token.getScopes()))
                .param("audience", audience.value())
                .param("expiresAt", Timestamps.format(token.getExpiresAt()))
                .param("revokedAt", access.isInvalidated() || authorizationRevoked(authorization.getId())
                        ? now : null)
                .param("createdAt", Timestamps.format(token.getIssuedAt()))
                .param("authorizationId", authorization.getId())
                .update();

        // Recorded only when the token is new — the framework re-saves an authorization it read
        // back, and an audit trail that logged the same issuance three times would be worse than
        // none. `stored` was read before any of this write, so membership means it already existed.
        if (!stored.contains(hash)) {
            audit.record(new AuditEvent(subject, "token_issue", "access_token", hash,
                    Map.of("client_id", authorization.getRegisteredClientId(),
                            "grant_type", authorization.getAuthorizationGrantType().getValue(),
                            "authorization_id", authorization.getId(),
                            "scopes", String.join(" ", token.getScopes()))));
        }

        // **An invalidated access token ends the authorization, and that is the documented contract.**
        // §撤销 says a revocation takes the code, the access token and the refresh token together. It
        // only happened for the refresh token, so `POST /oauth/revoke` with an access token left the
        // refresh token live — a client that revoked what it had would be told it succeeded and keep
        // renewing for months. It is safe to read "invalidated" this way because a *refresh* never
        // invalidates the access token it replaces: the framework's refresh provider contains no
        // invalidate call at all (checked in 7.1.1's bytecode), so the only ways to arrive here are
        // the revocation endpoint and reuse detection.
        if (access.isInvalidated() && !authorizationRevoked(authorization.getId())) {
            revokeAuthorization(authorization.getId(), now, "access_token_revoked");
        }
    }

    /**
     * Writes the refresh token, and decides whether this save was a rotation or an ending.
     *
     * <p><strong>The rule is one comparison, and it is the whole of the chain's bookkeeping.</strong>
     * Compare the incoming token with the one currently live under this authorization:
     *
     * <ul>
     *   <li><em>Different</em> — a rotation. The live row becomes this row's {@code rotated_from} and
     *       is revoked, which is what the recounting of a replay walks back along (ADR 0024).
     *   <li><em>The same, and invalidated</em> — an ending. {@code POST /oauth/revoke} hands the
     *       framework the very token it already holds and marks it invalid; that is a client saying
     *       "this authorization is over", so the whole authorization goes with it.
     * </ul>
     *
     * <p><strong>The trigger recorded here names the row that arrived invalidated, not always the
     * cause.</strong> One other thing reaches this branch: a replayed authorization code, where the
     * framework invalidates the refresh token it issued (the code's own provider prefers it over the
     * access token — read in 7.1.1's bytecode). So the trail says {@code refresh_token_revoked} for a
     * revocation that was really reuse detection. It is left that way rather than guessed at, because
     * the two cannot be told apart from inside this method: a code that has been exchanged is
     * invalidated too, so "the code was used" is true on every save that follows an exchange. The
     * cause is still recoverable from the same trail — the code's own row carries its {@code used_at}
     * and the authorization is one row away.
     *
     * <p>The distinction is not cosmetic. Rotation without it would look like revocation (the
     * framework drops the old token from the object), and treating it as such would tear down the
     * chain on every single refresh — a logout on a timer.
     */
    void upsertRefreshToken(OAuth2Authorization authorization,
            OAuth2Authorization.Token<OAuth2RefreshToken> refresh, Set<String> stored, String now) {
        String hash = TokenRowMapper.toHash(refresh.getToken().getTokenValue(), stored);
        String live = liveRefreshHash(authorization.getId());
        boolean rotated = live != null && !live.equals(hash);

        if (refresh.isInvalidated() && !rotated) {
            revokeAuthorization(authorization.getId(), now, "refresh_token_revoked");
            return;
        }

        OAuth2RefreshToken token = refresh.getToken();
        jdbc.sql("INSERT INTO refresh_token (token_hash, client_id, user_id, scope, expires_at,"
                        + " revoked_at, rotated_from, created_at, authorization_id)"
                        + " VALUES (:hash, :clientId, :userId, :scope, :expiresAt, :revokedAt,"
                        + " :rotatedFrom, :createdAt, :authorizationId)"
                        + " ON CONFLICT (token_hash) DO UPDATE SET"
                        + " revoked_at = COALESCE(refresh_token.revoked_at, EXCLUDED.revoked_at)")
                .param("hash", hash)
                .param("clientId", authorization.getRegisteredClientId())
                .param("userId", authorization.getPrincipalName())
                .param("scope", String.join(" ", authorization.getAuthorizedScopes()))
                .param("expiresAt", Timestamps.format(token.getExpiresAt()))
                .param("revokedAt", refresh.isInvalidated() || authorizationRevoked(authorization.getId())
                        ? now : null)
                .param("rotatedFrom", rotated ? live : null)
                .param("createdAt", Timestamps.format(token.getIssuedAt()))
                .param("authorizationId", authorization.getId())
                .update();

        if (rotated) {
            // The predecessor is spent the moment its successor exists. Left live, it would be a
            // second working credential for the same authorization.
            jdbc.sql("UPDATE refresh_token SET revoked_at = :now WHERE token_hash = :hash"
                            + " AND revoked_at IS NULL")
                    .param("now", now).param("hash", live).update();
        }
    }

    /**
     * Deletes the authorization and everything under it.
     *
     * <p>Children first, and not because of tidiness: {@code V6} made the three token tables point at
     * {@code oauth_authorization}, so the parent cannot go while a child remains.
     *
     * <p>This is not how revocation works. A revocation marks rows; it does not remove them, because a
     * token that was revoked has to stay findable for the replay rule to reach the chain it belongs to.
     */
    void remove(OAuth2Authorization authorization) {
        String id = authorization.getId();
        jdbc.sql("DELETE FROM auth_code WHERE authorization_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM access_token WHERE authorization_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM refresh_token WHERE authorization_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM oauth_authorization WHERE id = :id").param("id", id).update();
    }

    /** The authorization's own values as we have them stored — the read-modify-write guard's input. */
    Set<String> storedTokenValues(String authorizationId) {
        List<String> hashes = jdbc.sql("SELECT code_hash AS value FROM auth_code WHERE authorization_id = :id"
                        + " UNION ALL SELECT token_hash FROM access_token WHERE authorization_id = :id"
                        + " UNION ALL SELECT token_hash FROM refresh_token WHERE authorization_id = :id")
                .param("id", authorizationId)
                .query(String.class)
                .list();
        return new LinkedHashSet<>(hashes);
    }

    String liveRefreshHash(String authorizationId) {
        return jdbc.sql("SELECT token_hash FROM refresh_token WHERE authorization_id = :id"
                        + " AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1")
                .param("id", authorizationId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /**
     * Ends an authorization: its code, its access tokens and its refresh tokens, together. See
     * {@link TokenStore#revokeAuthorization} for why the whole authorization is the right scope, and
     * {@code trigger} — one act, but not always the same act.
     */
    void revokeAuthorization(String authorizationId, String now, String trigger) {
        // **The authorization's own row first, because it is the authority** (V9). Everything below
        // marks rows to match; this is the fact they match. Landing it first means a reader that
        // arrives in the middle of the sweep already sees an ended authorization, rather than one
        // whose remaining live rows are a matter of timing.
        int ended = jdbc.sql("UPDATE oauth_authorization SET revoked_at = :now"
                        + " WHERE id = :id AND revoked_at IS NULL")
                .param("now", now).param("id", authorizationId).update();
        if (ended == 0) {
            // Already over. Not an error and not a second audit row: the callers above reach here
            // from a load that can happen more than once for one revocation (the framework re-saves
            // what it read back), and a trail that recorded the same logout twice would describe the
            // calls rather than the event.
            return;
        }

        jdbc.sql("UPDATE auth_code SET used_at = :now WHERE authorization_id = :id AND used_at IS NULL")
                .param("now", now).param("id", authorizationId).update();
        jdbc.sql("UPDATE access_token SET revoked_at = :now WHERE authorization_id = :id"
                        + " AND revoked_at IS NULL")
                .param("now", now).param("id", authorizationId).update();
        jdbc.sql("UPDATE refresh_token SET revoked_at = :now WHERE authorization_id = :id"
                        + " AND revoked_at IS NULL")
                .param("now", now).param("id", authorizationId).update();

        // One row for the authorization rather than one per token: the person did one thing — they
        // logged out — and a trail that listed the three rows it happened to touch would be a
        // description of the schema rather than of the event.
        audit.record(new AuditEvent(subjectOfAuthorizationRow(authorizationId), "token_revoke",
                "authorization", authorizationId, Map.of("trigger", trigger)));
    }

    /**
     * Whether this authorization has been ended. See {@link TokenStore#authorizationRevoked} for why
     * this counts rather than reading the column.
     */
    boolean authorizationRevoked(String authorizationId) {
        return jdbc.sql("SELECT count(*) FROM oauth_authorization"
                        + " WHERE id = :id AND revoked_at IS NOT NULL")
                .param("id", authorizationId)
                .query(Long.class)
                .single() > 0;
    }

    /** The subject recorded on an authorization's own row, for an audit entry written after the fact. */
    private String subjectOfAuthorizationRow(String authorizationId) {
        return jdbc.sql("SELECT principal_name FROM oauth_authorization WHERE id = :id")
                .param("id", authorizationId)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
