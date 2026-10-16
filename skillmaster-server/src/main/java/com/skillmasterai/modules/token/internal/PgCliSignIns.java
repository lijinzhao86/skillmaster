package com.skillmasterai.modules.token.internal;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.account.AuthThrottle;
import com.skillmasterai.modules.account.Throttle;
import com.skillmasterai.modules.account.ThrottledException;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.token.CliSignIn;
import com.skillmasterai.modules.token.CliSignInPolicy;
import com.skillmasterai.modules.token.TokenPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * The CLI's login, written against the tables M2 already owns (v4 Q5: our code writes that row, not
 * the framework's authorization server).
 *
 * <p><strong>Why not the framework's authorization-code flow for this.</strong> That flow is the
 * third party's road and stays theirs (Q4). It also cannot express what this one needs: a per-login
 * name, a credential whose two rulers are the session's, and a refusal vocabulary a person can act
 * on. Sharing the flow would mean bending it in three directions, and the one thing it does give us —
 * the token minting — is twenty lines.
 *
 * <p><strong>What is shared, and deliberately.</strong> {@link TokenStore} still writes the rows, so
 * this class describes the act and the store decides the shape: hashes in, audience stamped, the
 * issuance audited once. Minting a credential here by hand would be a second opinion about what a
 * token row is, and the validator would have to be told about both.
 *
 * <p><strong>The credential's value exists in exactly one response and nowhere else.</strong> It is
 * generated at {@link #exchange}, written as a sha256, and returned to the caller that redeemed the
 * code. It is not in the code table, not in a log and not in the audit trail — an audit row here says
 * that a login happened, never what it produced.
 */
public final class PgCliSignIns implements CliSignIn {

    /** A one-time code: 256 bits, which is what makes a guess at the exchange hopeless (T14). */
    private final StringKeyGenerator codes =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

    /**
     * The credential itself, in the framework's own shape — the same construction
     * {@code RefreshTokenForPublicClients} uses for the other half of a token pair, so a value
     * produced here is indistinguishable from one the framework would have produced.
     */
    private final StringKeyGenerator credentials =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

    private final JdbcClient jdbc;
    private final RegisteredClientRepository clients;
    private final TokenStore authorizations;
    private final TokenPolicy tokenPolicy;
    private final CliSignInPolicy cliSignInPolicy;
    private final AuthThrottle authThrottle;
    private final AuditLog audit;

    public PgCliSignIns(JdbcClient jdbc, RegisteredClientRepository clients, TokenStore authorizations,
            TokenPolicy tokenPolicy, CliSignInPolicy cliSignInPolicy, AuthThrottle authThrottle,
            AuditLog audit) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.authorizations = authorizations;
        this.tokenPolicy = tokenPolicy;
        this.cliSignInPolicy = cliSignInPolicy;
        this.authThrottle = authThrottle;
        this.audit = audit;
    }

    /**
     * Records the consent, issues the code, and ends the login this one replaces.
     *
     * <p><strong>The authorization row is written here and its credential is not.</strong> That is
     * v4's split, and it is forced rather than chosen: a credential's value cannot survive from this
     * call to the exchange (only its hash is ever stored, and the row it hashes is what the exchange
     * would have to read back), so {@link #exchange} is what mints. What is written here is the part
     * that has to be written before the person leaves this screen — who agreed, to what, and on which
     * machine — plus the code that carries the delivery.
     */
    @Override
    public Started start(String userId, Start request, String approvedFrom) {
        RegisteredClient client = clients.findByClientId(request.clientId());
        if (client == null) {
            // Loud, and naming the row to seed, in the shape ClientRegistry already uses for a
            // mis-seeded deployment: a login that cannot be started is a deployment fact, not a
            // request the caller got wrong.
            throw new IllegalStateException("no oauth_client row for '" + request.clientId()
                    + "' — this deployment cannot start a login. Seed it (see the server README).");
        }
        Set<String> scopes = intersect(request.scopes(), client.getScopes());
        if (scopes.isEmpty()) {
            // The caller asked for nothing this client may have. Both sides are ours, so this is a
            // registration or a CLI that drifted, and a 500 naming the two sets fixes it faster than
            // a 400 that hides which of them is wrong.
            throw new IllegalStateException("'" + client.getClientId() + "' is registered for "
                    + client.getScopes() + ", and none of those were asked for (asked for "
                    + request.scopes() + ")");
        }

        endPreviousLogin(userId, request.name());

        Instant now = Instant.now();
        String authorizationId = Ulid.generate();
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(authorizationId)
                .principalName(userId)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(scopes)
                // The framework's canonical principal, which is also the shape TokenStore normalises
                // ours into on the way to the column: writing it directly keeps a row this class
                // created indistinguishable from one the framework did.
                .attribute(Principal.class.getName(),
                        UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of()))
                .build();
        authorizations.save(authorization);
        // **The name and the address, in a second statement because of them.** `TokenStore.
        // upsertAuthorization` names its columns explicitly and neither is among them, which is the
        // same property that keeps both safe from every later save the framework makes (V15, V16).
        // Writing them here, in the same transaction as the row they belong to, is the price of that
        // safety — and it is the only moment the address exists at all.
        jdbc.sql("UPDATE oauth_authorization SET label = :label, address = :address WHERE id = :id")
                .param("label", request.name())
                .param("address", approvedFrom)
                .param("id", authorizationId)
                .update();

        String code = codes.generateKey();
        jdbc.sql("INSERT INTO cli_sign_in_code (code_hash, authorization_id, code_challenge,"
                        + " expires_at, created_at) VALUES (:hash, :authorizationId, :challenge,"
                        + " :expiresAt, :now)")
                .param("hash", hashOf(code))
                .param("authorizationId", authorizationId)
                .param("challenge", request.codeChallenge())
                .param("expiresAt",
                        Timestamps.format(now.plus(tokenPolicy.authorizationCode())))
                .param("now", Timestamps.format(now))
                .update();

        // The product's own event, and it carries no secret: the name is on a screen, the client and
        // the scopes are the consent, and the id is already in the console's address space.
        audit.record(new AuditEvent(userId, "cli_sign_in", "authorization", authorizationId,
                Map.of("name", request.name(), "client_id", client.getClientId(),
                        "scopes", String.join(" ", scopes))));

        return new Started(redirectTo(client, "code=" + code, request.state()));
    }

    @Override
    public Optional<Credential> exchange(String code, String codeVerifier, String callerAddress) {
        // **Counted first, before the code table is touched at all.** Nothing here is worth rate
        // limiting except the work itself, and a request already refused should not also spend
        // anything — the ordering SendVerificationCodeUseCase argues for, applied to the one public
        // write this server has.
        Throttle throttle = authThrottle.countCliSignInExchange(callerAddress);
        if (throttle instanceof Throttle.Refused refused) {
            throw new ThrottledException(refused.retryAfterSeconds());
        }
        if (code == null || code.isBlank() || codeVerifier == null || codeVerifier.isBlank()) {
            return Optional.empty();
        }

        // **One statement, so "once" is atomic rather than intended.** The row is spent by the same
        // statement that finds it, so two exchanges racing cannot both see it live — the same shape
        // PgAuthThrottle.spend uses to count an attempt, and for the same reason.
        Optional<SpentCode> spent = jdbc.sql("UPDATE cli_sign_in_code SET used_at = :now"
                        + " WHERE code_hash = :hash AND used_at IS NULL AND expires_at > :now"
                        + " RETURNING authorization_id, code_challenge")
                .param("now", Timestamps.now())
                .param("hash", hashOf(code))
                .query((rs, rowNum) -> new SpentCode(rs.getString("authorization_id"),
                        rs.getString("code_challenge")))
                .optional();
        if (spent.isEmpty()) {
            return Optional.empty();
        }
        // Checked after the spend rather than before it: a wrong verifier burns the code, which is
        // correct — the code was handed to one process, and something else holding it is the case this
        // check exists for.
        if (!challengeOf(codeVerifier).equals(spent.get().challenge())) {
            return Optional.empty();
        }

        String authorizationId = spent.get().authorizationId();
        OAuth2Authorization authorization = authorizations.findById(authorizationId);
        // **Asked of the store rather than read here, and the first version of this got it wrong.**
        // Reading `revoked_at` into a String and testing it for null cannot tell a live row from a
        // missing one — both arrive as an empty Optional — so the first version answered "ended" for
        // every fresh login and refused every redemption. TokenStore.authorizationRevoked counts
        // instead, which is the shape that has no such hole, and it is already the answer the rest of
        // this module defers to.
        if (authorization == null || authorizations.authorizationRevoked(authorizationId)) {
            // The login was ended between the click and the redeem — the console's "end this login",
            // or a repeated login on the same machine. The code was spent above either way, so a
            // second attempt is answered exactly like this one.
            return Optional.empty();
        }

        Instant now = Instant.now();
        String value = credentials.generateKey();
        Set<String> scopes = authorization.getAuthorizedScopes();
        // The one fact the client cannot work out on its own, read off the row that was just
        // confirmed live: the terminal prints it beside the scopes and the expiry (prd 验收 #1), and
        // this is the last moment anybody here knows it.
        String name = labelOf(authorizationId);
        // **The idle ruler, set at issuance.** A fresh login gets the full window — which is also why
        // the first request after a login never rewrites the row (CliSignInPolicy.stale): what it
        // finds is already what a slide would write. The ceiling cannot bind on a fresh row, so the
        // two arguments being the same instant is not a shortcut.
        Instant expiresAt = cliSignInPolicy.slidExpiry(now, now);

        authorizations.save(OAuth2Authorization.from(authorization)
                .token(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, value, now, expiresAt,
                        scopes))
                .build());

        // The value is returned and never recorded — not here, not in the audit row TokenStore writes
        // for the issuance, not in any log. Whoever holds it now is the machine that asked.
        return Optional.of(new Credential(name, value, expiresAt, scopes));
    }

    /**
     * The machine's name, off the authorization row — the value {@code start} wrote and the console
     * shows.
     *
     * <p><strong>Asked here, and only after the login has been found live.</strong> The column is the
     * only place this value exists (V15: it is deliberately not in the framework's attribute bag) and
     * the exchange has nothing else to ask — no session, and a code that is a digest. The row is the
     * one the spent code named, so a name from anywhere else would be a second answer to "what did the
     * person call this machine".
     *
     * <p>It cannot be null on this path: a row is a CLI login only because {@link #start} wrote a
     * non-blank name to it (V15, and the endpoint refuses a blank one), and the code that reached here
     * was issued for that row. There is deliberately no fallback — an invented name printed on a
     * terminal is worse than an error here.
     */
    private String labelOf(String authorizationId) {
        return jdbc.sql("SELECT label FROM oauth_authorization WHERE id = :id")
                .param("id", authorizationId)
                .query(String.class)
                .single();
    }

    @Override
    public List<Login> listFor(String userId) {
        // **One statement, and the live-ness test is the credential's own expiry.** `expires_at`
        // already carries both rulers — it is `min(last use + idle, issuance + absolute)` — so
        // "later than now" is the whole of "this login can still be used". Nothing sweeps these rows:
        // they stay in `oauth_authorization` after the credential dies, and a console row for a login
        // nobody can make a request with would be a device that is not there (the count on that screen
        // is the length of this list).
        //
        // **The token's existence is required, and that is deliberate.** An authorization whose code
        // was never redeemed has no credential at all — the CLI died in between — so there is nothing
        // that could act as this person and nothing to end. Q7 replaces such a row when the same
        // machine asks again, so it does not accumulate either way.
        //
        // `label IS NOT NULL` is what makes a row a CLI login (V15): the framework writes every
        // third-party authorization, and none of them is a named machine's.
        return jdbc.sql("SELECT a.id, a.label, a.address, a.created_at, a.last_used_at"
                        + " FROM oauth_authorization a"
                        + " WHERE a.principal_name = :user AND a.label IS NOT NULL"
                        + " AND a.revoked_at IS NULL"
                        + " AND EXISTS (SELECT 1 FROM access_token t WHERE t.authorization_id = a.id"
                        + " AND t.revoked_at IS NULL AND t.expires_at > :now)"
                        + " ORDER BY a.created_at DESC")
                .param("user", userId)
                .param("now", Timestamps.now())
                .query(PgCliSignIns::readLogin)
                .list();
    }

    /** The five values the screen shows, with the one fallback {@link Login#lastUsedAt} explains. */
    private static Login readLogin(ResultSet rs, int rowNumber) throws SQLException {
        Instant signedInAt = Timestamps.parse(rs.getString("created_at"));
        String lastUsedAt = rs.getString("last_used_at");
        return new Login(rs.getString("id"), rs.getString("label"), rs.getString("address"),
                signedInAt, lastUsedAt == null ? signedInAt : Timestamps.parse(lastUsedAt));
    }

    @Override
    public boolean revoke(String userId, String authorizationId) {
        // Ownership and liveness in the WHERE clause, so "no such login" and "somebody else's login"
        // are one answer with no branch that could tell them apart (v4 §接口).
        Optional<String> mine = jdbc.sql("SELECT id FROM oauth_authorization"
                        + " WHERE id = :id AND principal_name = :user AND revoked_at IS NULL")
                .param("id", authorizationId)
                .param("user", userId)
                .query(String.class)
                .optional();
        if (mine.isEmpty()) {
            return false;
        }
        authorizations.revokeAuthorization(authorizationId, Timestamps.now(), "console");
        return true;
    }

    @Override
    public String refuse(String clientId, String state) {
        RegisteredClient client = clients.findByClientId(clientId);
        if (client == null) {
            // Nowhere to send a refusal, and guessing would be worse than failing: the address it would
            // be guessed from is the one this whole design refuses to take from the request.
            throw new IllegalStateException("no oauth_client row for '" + clientId + "' — there is"
                    + " nowhere to send a refusal");
        }
        return redirectTo(client, "error=access_denied", state);
    }

    /**
     * Ends the login this one replaces: the same person, the same machine name, still live (Q7).
     *
     * <p><strong>The name is the only thing the server can recognise a machine by, and that has a
     * cost worth stating.</strong> The CLI knows which credential it is holding; the server does not,
     * and ADR 0040 §决定 4 keeps the CLI out of revocation. So "the same machine" is resolved from
     * what the person typed, and two machines given the same name end each other. That is the price of
     * the name being identity — which the product already chose when the console row became
     * {@code CLI · <name>} rather than something the server derived.
     *
     * <p>It runs before the new row is written, so a failure to end the old one cannot leave two live
     * logins behind a successful answer.
     */
    private void endPreviousLogin(String userId, String name) {
        List<String> previous = jdbc.sql("SELECT id FROM oauth_authorization"
                        + " WHERE principal_name = :user AND label = :label AND revoked_at IS NULL")
                .param("user", userId)
                .param("label", name)
                .query(String.class)
                .list();
        String now = Timestamps.now();
        previous.forEach(id -> authorizations.revokeAuthorization(id, now, "cli_sign_in_replaced"));
    }

    /**
     * Where the browser goes next, read from the client's registered address rather than the request.
     *
     * <p><strong>The request cannot choose this.</strong> It arrives through a URL the caller
     * controls, and a redirect target that comes from there is an open redirect with a credential on
     * the end of it — the fixed loopback port exists precisely so that this address can be a
     * deployment fact (ADR 0028), and a client registered with more than one is one this path does not
     * know how to send a code to.
     */
    private static String redirectTo(RegisteredClient client, String parameter, String state) {
        Set<String> registered = client.getRedirectUris();
        if (registered.size() != 1) {
            throw new IllegalStateException("'" + client.getClientId() + "' is registered with "
                    + registered.size() + " redirect URIs, and a login can only be delivered to one");
        }
        String base = registered.iterator().next();
        // The state is handed back when there is one. A screen whose address arrived without it is the
        // "incomplete link" the design draws — the page refuses to submit that, and this is the
        // server's half of not inventing a correlation the CLI never asked for.
        String query = parameter + (state == null ? "" : "&state=" + state);
        return base + (base.contains("?") ? "&" : "?") + query;
    }

    /** Whether this login is over — the column the console's "end this login" writes. */
    /** Never widens: what the caller asked for, limited to what the client is registered for. */
    private static Set<String> intersect(Set<String> asked, Set<String> registered) {
        Set<String> kept = new LinkedHashSet<>(asked);
        kept.retainAll(registered);
        return Set.copyOf(kept);
    }

    /**
     * PKCE's S256: the challenge is the base64url of the verifier's digest.
     *
     * <p>The second implementation of this in the repository — the framework has one for its own flow
     * and the CLI has one in Go — and that is the price of not borrowing the authorization server for
     * a route that is deliberately not it. It is four lines and it has a test at both ends.
     */
    private static String challengeOf(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JRE; reaching here is a broken runtime, not a bad request.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hashOf(String value) {
        return Sha256Hex.of(value.getBytes(StandardCharsets.UTF_8));
    }

    /** The two columns a redemption needs, read by the statement that also spends the row. */
    private record SpentCode(String authorizationId, String challenge) {
    }
}
