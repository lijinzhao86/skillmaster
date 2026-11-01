package com.skillmasterai.modules.token.internal;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.token.TokenAudience;
import com.skillmasterai.modules.token.TokenPolicy;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * The framework's authorization model, kept in M2's six tables with no token value in them.
 *
 * <p>This is the whole of ADR 0023's "take the protocol, write the storage". Spring's own
 * {@code JdbcOAuth2AuthorizationService} writes {@code access_token_value} and
 * {@code refresh_token_value} verbatim, and its providers compare what the store returns against the
 * presented token with {@code String.equals}. That collides with ADR 0007 — a database leak must not
 * be a token leak — so the values here are sha256 and nothing else.
 *
 * <p><strong>How that works.</strong> Every value is mapped on the way in and on the way out. In:
 * sha256. Out: the presented value is put back exactly where its hash matches. It works because the
 * raw value is in hand at precisely the moment the framework is about to compare it — it is the
 * argument to {@link #findByToken}. Nothing else has to remember anything. Verified by a spike
 * through the real endpoints before any of this was written; see the M02 module doc.
 *
 * <p><strong>The one place it almost breaks, and the guard for it.</strong> {@link #findById} has no
 * presented value to put back, so it hands back hashes. If the framework then saves what it read —
 * which the consent flow does — hashing again would store the hash <em>of a hash</em> and the
 * authorization would be unusable from then on, silently. So {@link #save} asks which of the
 * incoming values it already has stored, and leaves those alone (see {@link TokenRowMapper#toHash}).
 *
 * <p><strong>What is not stored.</strong> Only the invalidated flag of the framework's per-token
 * metadata is meaningful to us, and it is the same event as our own {@code revoked_at} /
 * {@code used_at}, so it lives in those columns and is translated in both directions. Claims are the
 * one thing with no home, and only a JWT or an introspection response would want them: ADR 0021 chose
 * opaque tokens, validated in process.
 */
public final class TokenStore implements OAuth2AuthorizationService {

    private final JdbcClient jdbc;
    private final RegisteredClientRepository clients;
    private final TokenAudience audience;
    private final AuditLog audit;
    private final TokenPolicy policy;

    private final AuthorizationRowStore rows;
    private final AuthorizationLoader loader;

    public TokenStore(JdbcClient jdbc, RegisteredClientRepository clients, TokenAudience audience,
            AuditLog audit, TokenPolicy policy) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.audience = audience;
        this.audit = audit;
        this.policy = policy;
        this.rows = new AuthorizationRowStore(jdbc, audience, audit);
        this.loader = new AuthorizationLoader(jdbc, clients, policy, rows);
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        String id = authorization.getId();
        // Before any write: which of the values we are being handed are already hashes we stored.
        Set<String> stored = rows.storedTokenValues(id);
        String now = Timestamps.now();

        rows.upsertAuthorization(authorization);

        OAuth2Authorization.Token<OAuth2AuthorizationCode> code =
                authorization.getToken(OAuth2AuthorizationCode.class);
        if (code != null) {
            rows.upsertCode(authorization, code, stored, now);
        }

        OAuth2Authorization.Token<OAuth2AccessToken> access = authorization.getAccessToken();
        if (access != null) {
            rows.upsertAccessToken(authorization, access, stored, now);
        }

        OAuth2Authorization.Token<OAuth2RefreshToken> refresh = authorization.getRefreshToken();
        if (refresh != null) {
            rows.upsertRefreshToken(authorization, refresh, stored, now);
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
    @Override
    public void remove(OAuth2Authorization authorization) {
        rows.remove(authorization);
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return loader.load(id, null, null);
    }

    /**
     * Finds the authorization a token belongs to, and puts the token itself back.
     *
     * <p>The presented value replaces the hash in exactly the one slot whose hash it matches — so the
     * framework's {@code String.equals} against what it presented succeeds, while every other value in
     * the object stays a hash. That asymmetry is the mechanism, not an oversight.
     *
     * @param tokenType null when the caller does not know which kind of token this is, which is the
     *                  revocation endpoint's case: it is handed a string and must find out.
     */
    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        if (token == null) {
            return null;
        }
        String hash = Sha256Hex.of(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Optional<String> authorizationId = findAuthorizationId(token, hash);
        return authorizationId.map(id -> loader.load(id, token, hash)).orElse(null);
    }

    /**
     * Ends an authorization: its code, its access tokens and its refresh tokens, together.
     *
     * <p>All three, not just the one that was presented. The design's reason for
     * {@code authorization_id} existing at all is that revoking must not leave a live access token to
     * run out its hour — "the client logged out" and "the client still works for fifty more minutes"
     * cannot both be true.
     *
     * <p><strong>Why the whole authorization is the right scope, and not a walk along
     * {@code rotated_from}.</strong> ADR 0024 says the chain is what comes down — "撤销沿
     * {@code rotated_from} 回溯，而不是按 {@code (client_id, user_id)} 一锅端" — and the contrast it
     * is drawing is with the blunt key that would hit the same person's other device. This is the same
     * scope by a shorter route: one rotation never changes {@code authorization_id}, and one
     * authorization is one sign-in on one device. So every refresh token of an authorization is that
     * authorization's family and nothing else's. The two differ only in the case the mark exists for:
     * the grace window lets a chain fork (ADR 0024's 后果), and both branches are still this family —
     * walking {@code rotated_from} from the replayed branch would leave the other branch alive, which
     * is a worse answer, not a stricter one.
     *
     * @param trigger what ended it, for the audit trail — one act, but not always the same act.
     */
    // Package-private since v4, so that PgCliSignIns ends one login through the same five statements
    // the logout path uses, rather than a second copy of them. Its callers own the ownership check —
    // this ends what it is told to end.
    void revokeAuthorization(String authorizationId, String now, String trigger) {
        rows.revokeAuthorization(authorizationId, now, trigger);
    }

    /**
     * Whether this authorization has been ended — one query, and the answer everything else defers to.
     *
     * <p>Counting rather than reading the column, so that "the row exists and the column is NULL" and
     * "there is no row" cannot be confused by a mapping that has no way to say which it saw.
     */
    // Package-private since v4: PgCliSignIns asks this before delivering a credential, and it is the
    // same question the rest of this module asks — one answer, not a second opinion.
    boolean authorizationRevoked(String authorizationId) {
        return rows.authorizationRevoked(authorizationId);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Which row the presented value belongs to.
     *
     * <p><strong>The token type is not used, and that is the framework's own rule.</strong>
     * {@code JdbcOAuth2AuthorizationService.findByToken} matches the value against every column that
     * can hold one — state, code, access token, refresh token — and never consults the type it was
     * given. It has to work that way, because the type it is given is often not the kind of thing
     * being presented: the code exchange looks the authorization up by <em>code</em> under the type
     * {@code "code"}, and the consent step looks it up by <em>state</em> under {@code "state"}. Both
     * are named types this service never mints, so a lookup that trusted the type would find nothing
     * and answer {@code invalid_grant} to a perfectly good code.
     *
     * <p>Doing the same is safe rather than sloppy: every token here is 96 bytes of base64url, so a
     * hash appearing in two tables is not a case that can arise. The order below only decides which
     * row is found first if that impossible thing happened.
     *
     * <p>The state is matched as the raw string, not as a hash: it is a lookup key rather than a
     * credential, and the framework compares it by equality against what it stored (V7).
     */
    private Optional<String> findAuthorizationId(String token, String hash) {
        Optional<String> found = authorizationIdIn("access_token", "token_hash", hash);
        if (found.isEmpty()) {
            found = authorizationIdIn("refresh_token", "token_hash", hash);
        }
        if (found.isEmpty()) {
            found = authorizationIdIn("auth_code", "code_hash", hash);
        }
        return found.isPresent() ? found : byState(token);
    }

    private Optional<String> byState(String state) {
        return jdbc.sql("SELECT id FROM oauth_authorization WHERE state = :state")
                .param("state", state)
                .query(String.class)
                .optional();
    }

    private Optional<String> authorizationIdIn(String table, String column, String hash) {
        // The table and column names are literals chosen by the branch above, never caller input.
        return jdbc.sql("SELECT authorization_id FROM " + table + " WHERE " + column + " = :hash")
                .param("hash", hash)
                .query(String.class)
                .optional();
    }
}
