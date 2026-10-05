package com.skillmasterai.modules.token.internal;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.token.TokenAudience;
import com.skillmasterai.modules.token.TokenPolicy;
import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import tools.jackson.databind.json.JsonMapper;

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
 * incoming values it already has stored, and leaves those alone (see {@link #toHash}).
 *
 * <p><strong>What is not stored.</strong> Only the invalidated flag of the framework's per-token
 * metadata is meaningful to us, and it is the same event as our own {@code revoked_at} /
 * {@code used_at}, so it lives in those columns and is translated in both directions. Claims are the
 * one thing with no home, and only a JWT or an introspection response would want them: ADR 0021 chose
 * opaque tokens, validated in process.
 */
public final class TokenStore implements OAuth2AuthorizationService {

    /**
     * The framework's own composition, not a hand-assembled one.
     *
     * <p>{@code attributes} carries live objects, not strings: {@code OAuth2AuthorizationRequest} is
     * what the PKCE check reads back, and the principal is an {@code Authentication}. Serializing them
     * takes the mixins the framework ships, and asking {@code SecurityJacksonModules} for them is how
     * the framework's own JDBC store configures its mapper — so an upgrade that changes the mixins
     * changes both together.
     */
    private static final JsonMapper JSON = JsonMapper.builder()
            .addModules(SecurityJacksonModules.getModules(TokenStore.class.getClassLoader()))
            .build();

    private static final String INVALIDATED = OAuth2Authorization.Token.INVALIDATED_METADATA_NAME;

    private final JdbcClient jdbc;
    private final RegisteredClientRepository clients;
    private final TokenAudience audience;
    private final AuditLog audit;
    private final TokenPolicy policy;

    public TokenStore(JdbcClient jdbc, RegisteredClientRepository clients, TokenAudience audience,
            AuditLog audit, TokenPolicy policy) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.audience = audience;
        this.audit = audit;
        this.policy = policy;
    }

    /**
     * Who a token issued under this authorization acts as.
     *
     * <p>For everything a person does, that is the principal the framework recorded — the userId M1
     * put in the session, which the consent page then agreed to (ADR 0014).
     *
     * <p><strong>For {@code client_credentials} it is not.</strong> There is no person in that flow,
     * so the framework's principal is the *client*, and a token whose {@code user_id} were a client
     * id would be a token no user id column should accept: {@code access_token.user_id} references
     * {@code app_user}, and {@code AuthenticatedSubject} refuses anything that is not a ULID. Who an
     * unattended client acts as is therefore fixed when it is registered — {@code oauth_client.user_id}
     * (ADR 0022) — and a row without it is a registration mistake, not a case to default through.
     */
    private String subjectOf(OAuth2Authorization authorization) {
        if (!AuthorizationGrantType.CLIENT_CREDENTIALS.getValue()
                .equals(authorization.getAuthorizationGrantType().getValue())) {
            return authorization.getPrincipalName();
        }
        return jdbc.sql("SELECT user_id FROM oauth_client WHERE client_id = :id")
                .param("id", authorization.getRegisteredClientId())
                .query(String.class)
                .optional()
                .filter(userId -> !userId.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "client '" + authorization.getRegisteredClientId() + "' uses"
                                + " client_credentials but oauth_client.user_id is not set; it has to"
                                + " name the account it acts as (ADR 0022)"));
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        String id = authorization.getId();
        // Before any write: which of the values we are being handed are already hashes we stored.
        Set<String> stored = storedTokenValues(id);
        String now = Timestamps.now();

        upsertAuthorization(authorization);

        OAuth2Authorization.Token<OAuth2AuthorizationCode> code =
                authorization.getToken(OAuth2AuthorizationCode.class);
        if (code != null) {
            upsertCode(authorization, code, stored, now);
        }

        OAuth2Authorization.Token<OAuth2AccessToken> access = authorization.getAccessToken();
        if (access != null) {
            upsertAccessToken(authorization, access, stored, now);
        }

        OAuth2Authorization.Token<OAuth2RefreshToken> refresh = authorization.getRefreshToken();
        if (refresh != null) {
            upsertRefreshToken(authorization, refresh, stored, now);
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
        String id = authorization.getId();
        jdbc.sql("DELETE FROM auth_code WHERE authorization_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM access_token WHERE authorization_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM refresh_token WHERE authorization_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM oauth_authorization WHERE id = :id").param("id", id).update();
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return load(id, null, null);
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
        return authorizationId.map(id -> load(id, token, hash)).orElse(null);
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    private void upsertAuthorization(OAuth2Authorization authorization) {
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
                .param("attributes", JSON.writeValueAsString(attributesToStore(authorization)))
                .param("state", stateOf(authorization))
                // Preserved across updates by the conflict clause above not naming it.
                .param("createdAt", Timestamps.now())
                .update();
    }

    /**
     * The attributes as stored, with one substitution the framework's own deserializer requires.
     *
     * <p>The framework writes the authorizing principal in as an {@code Authentication} — and on this
     * deployment that object is M1's {@code WebAuthentication}, which the framework's polymorphic type
     * validator refuses to read back. Refusing is the validator doing its job: it allows Spring
     * Security's own types and nothing else, because deserializing an arbitrary type named by stored
     * JSON is a gadget surface. Widening that allowlist to reach a class of ours would trade a real
     * protection for a convenience.
     *
     * <p>So the value is normalised instead, to the framework's canonical principal,
     * {@code UsernamePasswordAuthenticationToken} — the type form login puts there and the one the
     * framework ships a deserializer for. What the reader needs is the name, which is the userId in
     * both, and nothing else about the object is read.
     *
     * <p>This is the one place attributes are not stored verbatim, and it is not pruning: no key is
     * dropped and no key's meaning changes.
     */
    private static Map<String, Object> attributesToStore(OAuth2Authorization authorization) {
        Map<String, Object> attributes = new LinkedHashMap<>(authorization.getAttributes());
        Object principal = attributes.get(Principal.class.getName());
        if (principal instanceof Authentication authentication
                && !(principal instanceof UsernamePasswordAuthenticationToken)) {
            attributes.put(Principal.class.getName(), UsernamePasswordAuthenticationToken.authenticated(
                    authentication.getName(), null, authentication.getAuthorities()));
        }
        return attributes;
    }

    /**
     * The state the framework will look this authorization up by, from wherever it put it.
     *
     * <p>Two places, depending on how far the request has got: the {@code STATE} attribute once the
     * authorization endpoint has handled it, and the authorization request's own state before that.
     * The attribute is what the consent step looks up, so it is preferred — see V7.
     */
    private static String stateOf(OAuth2Authorization authorization) {
        Object attribute = authorization.getAttribute(OAuth2ParameterNames.STATE);
        if (attribute != null) {
            return String.valueOf(attribute);
        }
        Object request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        return request instanceof OAuth2AuthorizationRequest oauthRequest
                ? oauthRequest.getState()
                : null;
    }

    private void upsertCode(OAuth2Authorization authorization,
            OAuth2Authorization.Token<OAuth2AuthorizationCode> code, Set<String> stored, String now) {
        // The three columns the framework never reads back but the design keeps — which redirect the
        // code was issued for, and the PKCE challenge it is bound to — live in the authorization
        // request inside `attributes`. Absent means the code cannot be verified at all, so this fails
        // rather than writing a row with empty values that would look fine.
        OAuth2AuthorizationRequestFields request = authorizationRequestFields(authorization);

        jdbc.sql("INSERT INTO auth_code (code_hash, client_id, user_id, redirect_uri, scope,"
                        + " code_challenge, method, resource, expires_at, used_at, authorization_id)"
                        + " VALUES (:hash, :clientId, :userId, :redirectUri, :scope, :challenge,"
                        + " :method, :resource, :expiresAt, :usedAt, :authorizationId)"
                        // COALESCE, not EXCLUDED: a spent code stays spent. The framework re-saves an
                        // authorization it read back, and a plain assignment would clear the flag.
                        + " ON CONFLICT (code_hash) DO UPDATE SET"
                        + " used_at = COALESCE(auth_code.used_at, EXCLUDED.used_at)")
                .param("hash", toHash(code.getToken().getTokenValue(), stored))
                .param("clientId", authorization.getRegisteredClientId())
                .param("userId", subjectOf(authorization))
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

    private void upsertAccessToken(OAuth2Authorization authorization,
            OAuth2Authorization.Token<OAuth2AccessToken> access, Set<String> stored, String now) {
        OAuth2AccessToken token = access.getToken();
        String hash = toHash(token.getTokenValue(), stored);
        String subject = subjectOf(authorization);

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
    private void upsertRefreshToken(OAuth2Authorization authorization,
            OAuth2Authorization.Token<OAuth2RefreshToken> refresh, Set<String> stored, String now) {
        String hash = toHash(refresh.getToken().getTokenValue(), stored);
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
                .param("userId", subjectOf(authorization))
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
    private void revokeAuthorization(String authorizationId, String now, String trigger) {
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
     * Whether this authorization has been ended — one query, and the answer everything else defers to.
     *
     * <p>Counting rather than reading the column, so that "the row exists and the column is NULL" and
     * "there is no row" cannot be confused by a mapping that has no way to say which it saw.
     */
    private boolean authorizationRevoked(String authorizationId) {
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

    private Set<String> storedTokenValues(String authorizationId) {
        List<String> hashes = jdbc.sql("SELECT code_hash AS value FROM auth_code WHERE authorization_id = :id"
                        + " UNION ALL SELECT token_hash FROM access_token WHERE authorization_id = :id"
                        + " UNION ALL SELECT token_hash FROM refresh_token WHERE authorization_id = :id")
                .param("id", authorizationId)
                .query(String.class)
                .list();
        return new LinkedHashSet<>(hashes);
    }

    private String liveRefreshHash(String authorizationId) {
        return jdbc.sql("SELECT token_hash FROM refresh_token WHERE authorization_id = :id"
                        + " AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1")
                .param("id", authorizationId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private OAuth2Authorization load(String authorizationId, String presentedValue, String presentedHash) {
        AuthorizationRow row = jdbc.sql("SELECT id, registered_client_id, principal_name, grant_type,"
                        + " authorized_scopes, attributes, created_at, revoked_at"
                        + " FROM oauth_authorization WHERE id = :id")
                .param("id", authorizationId)
                .query((rs, rowNum) -> new AuthorizationRow(
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
                .authorizedScopes(scopesOf(row.authorizedScopes()))
                .attributes(attributes -> attributes.putAll(readAttributes(row.attributes())));

        codeToken(builder, row.id(), row.revoked(), presentedValue, presentedHash);
        accessToken(builder, row.id(), row.revoked(), presentedValue, presentedHash);
        refreshToken(builder, row.id(), row.createdAt(), row.revoked(), presentedValue, presentedHash);

        return builder.build();
    }

    private void codeToken(OAuth2Authorization.Builder builder, String authorizationId,
            boolean authorizationRevoked, String presentedValue, String presentedHash) {
        Optional<CodeRow> row = selectToken("SELECT code_hash AS hash, expires_at, used_at FROM auth_code",
                "code_hash", "expires_at DESC", authorizationId, presentedHash,
                (rs, n) -> new CodeRow(
                        rs.getString("hash"), rs.getString("expires_at"), rs.getString("used_at")));
        row.ifPresent(code -> builder.token(
                new OAuth2AuthorizationCode(value(code.hash(), presentedValue, presentedHash),
                        // The code has no issued-at column of its own; the authorization's creation is
                        // the same instant, and nothing reads it back except equality on the value.
                        Timestamps.parse(authorizationCreatedAt(authorizationId)),
                        Timestamps.parse(code.expiresAt())),
                metadata -> invalidated(metadata, authorizationRevoked || code.usedAt() != null)));
    }

    private void accessToken(OAuth2Authorization.Builder builder, String authorizationId,
            boolean authorizationRevoked, String presentedValue, String presentedHash) {
        Optional<AccessRow> row = selectToken("SELECT token_hash AS hash, scope, expires_at,"
                        + " revoked_at, created_at FROM access_token",
                "token_hash", "(revoked_at IS NULL) DESC, created_at DESC", authorizationId, presentedHash,
                (rs, n) -> new AccessRow(rs.getString("hash"), rs.getString("scope"),
                        rs.getString("expires_at"), rs.getString("revoked_at"), rs.getString("created_at")));
        row.ifPresent(access -> builder.token(
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                        value(access.hash(), presentedValue, presentedHash),
                        Timestamps.parse(access.createdAt()),
                        Timestamps.parse(access.expiresAt()),
                        scopesOf(access.scope())),
                metadata -> invalidated(metadata, authorizationRevoked || access.revokedAt() != null)));
    }

    /**
     * Puts the refresh token back, with ADR 0024's two rules applied to what the framework sees.
     *
     * <p>Both are enforced here rather than in {@link #save} because both are about what a caller is
     * *allowed to do next*, and the framework decides that from the object it gets back — a token it
     * sees as invalidated never reaches the point where it would issue a successor.
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
        Optional<RefreshRow> row = selectToken("SELECT token_hash AS hash, expires_at, revoked_at,"
                        + " created_at FROM refresh_token",
                "token_hash", "(revoked_at IS NULL) DESC, created_at DESC", authorizationId, presentedHash,
                (rs, n) -> new RefreshRow(rs.getString("hash"), rs.getString("expires_at"),
                        rs.getString("revoked_at"), rs.getString("created_at")));
        if (row.isEmpty()) {
            return;
        }
        RefreshRow refresh = row.get();

        boolean pastCeiling = pastAbsoluteCeiling(authorizationCreatedAt);
        boolean presented = presentedHash != null && refresh.hash().equals(presentedHash);
        // **The authorization's own row decides this, and it has to come first.** `familyAlive` asking
        // only the token rows is what made an ended authorization resumable: a row that escaped the
        // revocation's sweep reads as a live successor, so the family looks alive, so the grace window
        // forgives a spent token and hands out a fresh pair (V9).
        boolean familyAlive = !authorizationRevoked && liveRefreshHash(authorizationId) != null;
        boolean forgiven = !pastCeiling && presented && familyAlive
                && withinReplayGrace(refresh.revokedAt());
        boolean replayed = !pastCeiling && presented && familyAlive && !forgiven
                && refresh.revokedAt() != null;

        builder.token(
                new OAuth2RefreshToken(value(refresh.hash(), presentedValue, presentedHash),
                        Timestamps.parse(refresh.createdAt()),
                        Timestamps.parse(refresh.expiresAt())),
                metadata -> {
                    if (authorizationRevoked || pastCeiling) {
                        // Marked directly rather than through the row's own timestamp: the row may have
                        // escaped the sweep and have nothing to read.
                        invalidated(metadata, true);
                    } else if (!forgiven) {
                        invalidated(metadata, refresh.revokedAt());
                    }
                });

        if (replayed) {
            revokeAuthorization(authorizationId, Timestamps.now(), "refresh_token_replayed");
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
     * one — {@link #findById} — a decision has to be made, and the framework's model holds one access
     * token where our table holds every one this authorization ever issued. Live first, then newest:
     * the answer the caller is asking for is "the current state of this authorization", not "the
     * oldest token it ever had".
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

    private static void invalidated(Map<String, Object> metadata, String revokedOrUsedAt) {
        invalidated(metadata, revokedOrUsedAt != null);
    }

    /**
     * The same, said directly — and the boolean form is the one this store now reaches for first.
     *
     * <p>Its authority is the authorization's own row, not the token's: a token row that escaped the
     * sweep a revocation performs is still a token under an authorization that is over, and the
     * framework has to see it that way or it will happily issue a successor (V9).
     */
    private static void invalidated(Map<String, Object> metadata, boolean revoked) {
        if (revoked) {
            metadata.put(INVALIDATED, true);
        }
    }

    // ------------------------------------------------------------------
    // Small translations
    // ------------------------------------------------------------------

    /**
     * Puts the presented value back where its hash belongs, and leaves every other value alone.
     *
     * <p>{@code presentedValue} is null on the {@link #findById} path, where there is nothing to put
     * back and the hashes stand as they are.
     */
    private static String value(String storedHash, String presentedValue, String presentedHash) {
        return storedHash.equals(presentedHash) ? presentedValue : storedHash;
    }

    /**
     * Hashes a value on the way in — unless we stored it ourselves, which is the read-modify-write
     * case.
     *
     * <p>Without this the consent flow corrupts the authorization: it reads with {@link #findById},
     * gets hashes, and saves them back, and a second hash is not a hash of anything. Membership in
     * this authorization's own stored values is the test, and it cannot produce a false positive — a
     * freshly generated token is 96 bytes of base64url, never the 64 lowercase hex characters a
     * digest is.
     */
    private static String toHash(String value, Set<String> stored) {
        return stored.contains(value)
                ? value
                : Sha256Hex.of(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Set<String> scopesOf(String spaceSeparated) {
        return spaceSeparated.isBlank() ? Set.of() : new LinkedHashSet<>(List.of(spaceSeparated.split(" ")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readAttributes(String json) {
        return JSON.readValue(json, Map.class);
    }

    /**
     * The parts of the authorization request the code's own columns record.
     *
     * <p>They are read from {@code attributes} rather than passed in, because that is where the
     * framework put them and the framework is what validates against them. A missing request is an
     * error rather than an empty row: without it PKCE cannot be checked at all, and a code written
     * with empty challenge columns would look like a code nobody can redeem.
     */
    private static OAuth2AuthorizationRequestFields authorizationRequestFields(
            OAuth2Authorization authorization) {
        Object request = authorization.getAttribute(
                org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.class.getName());
        if (!(request instanceof org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest oauthRequest)) {
            throw new IllegalStateException("authorization " + authorization.getId()
                    + " carries a code but no OAuth2AuthorizationRequest attribute; PKCE cannot be"
                    + " checked without it, so the code must not be written");
        }
        Map<String, Object> parameters = oauthRequest.getAdditionalParameters();
        return new OAuth2AuthorizationRequestFields(
                oauthRequest.getRedirectUri(),
                String.valueOf(parameters.get("code_challenge")),
                String.valueOf(parameters.get("code_challenge_method")),
                resource(parameters));
    }

    private static String resource(Map<String, Object> parameters) {
        Object resource = parameters.get(org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.RESOURCE);
        if (resource == null) {
            return null;
        }
        // RFC 8707 allows several; the column is one string, and the stored value is for the record —
        // the framework validates the audience from the request, not from here.
        return resource instanceof List<?> list && !list.isEmpty()
                ? String.valueOf(list.getFirst())
                : String.valueOf(resource);
    }

    private record AuthorizationRow(String id, String registeredClientId, String principalName,
            String grantType, String authorizedScopes, String attributes, String createdAt,
            String revokedAt) {

        /** Whether this authorization has been ended — the authority for everything below it. */
        boolean revoked() {
            return revokedAt != null;
        }
    }

    private record CodeRow(String hash, String expiresAt, String usedAt) {
    }

    private record AccessRow(String hash, String scope, String expiresAt, String revokedAt,
            String createdAt) {
    }

    private record RefreshRow(String hash, String expiresAt, String revokedAt, String createdAt) {
    }

    private record OAuth2AuthorizationRequestFields(String redirectUri, String codeChallenge,
            String codeChallengeMethod, String resource) {
    }
}
