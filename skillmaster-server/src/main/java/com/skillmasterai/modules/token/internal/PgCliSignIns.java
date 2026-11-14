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
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The CLI's login over a link and a poll (v68, ADR 0200), written against the tables M2 owns.
 *
 * <p><strong>Why the loopback delivery is gone.</strong> The listener could only work where a
 * browser could reach the machine that asked, which is exactly where a cloud device is not. The
 * replacement keeps the same three properties — the approval is a session, the credential is minted
 * once, the value is never stored — and drops the one that could not travel.
 *
 * <p><strong>What is shared, and deliberately.</strong> {@link TokenStore} still writes the rows, so
 * this class describes the act and the store decides the shape: hashes in, audience stamped, the
 * issuance audited once. Minting a credential here by hand would be a second opinion about what a
 * token row is, and the validator would have to be told about both.
 *
 * <p><strong>The credential's value exists in exactly one response and nowhere else.</strong> It is
 * generated when the poll collects it, written as a sha256, and returned to the process that proved
 * it holds the secret and the PKCE verifier. It is not in the question row, not in a log and not in
 * the audit trail — an audit row here says that a login happened, never what it produced.
 */
public class PgCliSignIns implements CliSignIn {

    /** The ticket in the link and the secret kept by the CLI: 256 bits each. */
    private final StringKeyGenerator tickets =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);
    private final StringKeyGenerator secrets =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

    /**
     * The credential itself, in the framework's own shape — the same construction
     * {@code RefreshTokenForPublicClients} uses for the other half of a token pair, so a value
     * produced here is indistinguishable from one the framework would have produced.
     */
    private final StringKeyGenerator credentials =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

    /** How long a question waits for a person, and how often the CLI may ask. */
    private static final Duration QUESTION_LIFETIME = Duration.ofMinutes(10);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    private final JdbcClient jdbc;
    private final RegisteredClientRepository clients;
    private final TokenStore authorizations;
    private final CliSignInPolicy cliSignInPolicy;
    private final AuthThrottle authThrottle;
    private final AuditLog audit;
    private final TransactionTemplate transactions;

    public PgCliSignIns(JdbcClient jdbc, RegisteredClientRepository clients, TokenStore authorizations,
            CliSignInPolicy cliSignInPolicy, AuthThrottle authThrottle, AuditLog audit,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.authorizations = authorizations;
        this.cliSignInPolicy = cliSignInPolicy;
        this.authThrottle = authThrottle;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Started start(Start request, String callerAddress) {
        count(authThrottle.countDeviceSignInStart(callerAddress));
        RegisteredClient client = clientOrThrow(request.clientId());
        Set<String> scopes = intersect(request.scopes(), client.getScopes());
        if (scopes.isEmpty()) {
            // Both sides are ours, so this is a registration or a CLI that drifted, and a 500 naming
            // the two sets fixes it faster than a 400 that hides which of them is wrong.
            throw new IllegalStateException("'" + client.getClientId() + "' is registered for "
                    + client.getScopes() + ", and none of those were asked for (asked for "
                    + request.scopes() + ")");
        }
        String name = request.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a machine name is required");
        }

        Instant now = Instant.now();
        String ticket = tickets.generateKey();
        String deviceSecret = secrets.generateKey();
        jdbc.sql("INSERT INTO cli_device_sign_in (ticket_hash, device_secret_hash, client_id, scopes,"
                        + " code_challenge, name, address, created_at, expires_at)"
                        + " VALUES (:ticket, :secret, :client, :scopes, :challenge, :name, :address,"
                        + " :now, :expiresAt)")
                .param("ticket", hashOf(ticket))
                .param("secret", hashOf(deviceSecret))
                .param("client", client.getClientId())
                .param("scopes", String.join(" ", scopes))
                .param("challenge", request.codeChallenge())
                .param("name", name)
                .param("address", callerAddress)
                .param("now", Timestamps.format(now))
                .param("expiresAt", Timestamps.format(now.plus(QUESTION_LIFETIME)))
                .update();
        return new Started(ticket, deviceSecret, QUESTION_LIFETIME.toSeconds(),
                POLL_INTERVAL.toSeconds());
    }

    @Override
    public Optional<PublicView> view(String ticket, String callerAddress) {
        count(authThrottle.countDeviceSignInView(callerAddress));
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return rowOf(ticket).map(row -> new PublicView(statusOf(row, now), row.name(),
                row.clientId(), scopesOf(row.scopes()), row.address(), row.createdAt(),
                row.expiresAt(), ageMinutesOf(row.createdAt(), now)));
    }

    /**
     * Writes the authorization row and marks the question answered, in one transaction.
     *
     * <p><strong>The claim comes first, and it is one statement.</strong> Two sessions approving the
     * same question at once must not both write an authorization; the {@code UPDATE … RETURNING}
     * decides which one gets to, and the loser sees the same false as an expired link.
     */
    @Override
    public boolean approve(String userId, String ticket, String name, String approvedFrom) {
        String finalName = name == null ? "" : name.trim();
        if (finalName.isEmpty() || ticket == null || ticket.isBlank()) {
            return false;
        }
        // **One transaction, written explicitly rather than with an annotation.** The architecture
        // rule keeps `@Transactional` in `usecase..`, and this is a module: the claim and the
        // authorization it names have to commit together, so the template is what says so here.
        Boolean approved = transactions.execute(status ->
                approveInTransaction(userId, ticket, finalName, approvedFrom));
        return Boolean.TRUE.equals(approved);
    }

    private boolean approveInTransaction(String userId, String ticket, String finalName,
            String approvedFrom) {
        Instant now = Instant.now();
        String authorizationId = Ulid.generate();
        Optional<Claimed> claimed = jdbc.sql("UPDATE cli_device_sign_in"
                        + " SET approved_at = :now, authorization_id = :authorizationId"
                        + " WHERE ticket_hash = :ticket AND approved_at IS NULL"
                        + " AND denied_at IS NULL AND consumed_at IS NULL AND expires_at > :now"
                        + " RETURNING client_id, scopes")
                .param("now", Timestamps.format(now))
                .param("authorizationId", authorizationId)
                .param("ticket", hashOf(ticket))
                .query((rs, rowNum) -> new Claimed(rs.getString("client_id"), rs.getString("scopes")))
                .optional();
        if (claimed.isEmpty()) {
            return false;
        }

        RegisteredClient client = clientOrThrow(claimed.get().clientId());
        Set<String> scopes = intersect(scopesOf(claimed.get().scopes()), client.getScopes());
        endPreviousLogin(userId, finalName);
        writeAuthorization(userId, client, scopes, finalName, approvedFrom, authorizationId, now);

        // The product's own event, and it carries no secret: the name is on a screen, the client and
        // the scopes are the consent, and the id is already in the console's address space.
        audit.record(new AuditEvent(userId, "cli_sign_in", "authorization", authorizationId,
                Map.of("name", finalName, "client_id", client.getClientId(),
                        "scopes", String.join(" ", scopes))));
        return true;
    }

    @Override
    public boolean refuse(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        int updated = jdbc.sql("UPDATE cli_device_sign_in SET denied_at = :now"
                        + " WHERE ticket_hash = :ticket AND approved_at IS NULL"
                        + " AND denied_at IS NULL AND consumed_at IS NULL AND expires_at > :now")
                .param("now", Timestamps.now())
                .param("ticket", hashOf(ticket))
                .update();
        return updated > 0;
    }

    @Override
    public boolean cancel(String ticket, String deviceSecret) {
        if (ticket == null || ticket.isBlank() || deviceSecret == null || deviceSecret.isBlank()) {
            return false;
        }
        // **The secret first, and a mismatch is the same answer as no such ticket.** Holding the link
        // is not enough to end somebody else's question — it is the one thing that travelled through
        // chats and browser histories.
        Optional<Row> found = rowOf(ticket)
                .filter(row -> row.secretHash().equals(hashOf(deviceSecret)));
        if (found.isEmpty()) {
            return false;
        }
        // **One statement, and it does not care whether the approval already landed.** Marking the
        // question denied in the same row that reports whether it was approved closes the window the
        // two-step version had: an approval committing between the read and the write was invisible,
        // and the authorization it wrote would have stayed behind with no collector.
        // **The mark and the tidy-up commit together.** A denial that committed while the revocation
        // failed would tell the page the link is over while the authorization it wrote is still live,
        // with no collector left to take it — two halves of one fact that must not disagree.
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Optional<Stopped> stopped = jdbc.sql("UPDATE cli_device_sign_in SET denied_at = :now"
                            + " WHERE ticket_hash = :ticket AND denied_at IS NULL"
                            + " AND consumed_at IS NULL"
                            + " AND (expires_at > :now OR approved_at IS NOT NULL)"
                            + " RETURNING approved_at, authorization_id")
                    .param("now", Timestamps.now())
                    .param("ticket", hashOf(ticket))
                    .query((rs, row) -> new Stopped(rs.getString("approved_at"),
                            rs.getString("authorization_id")))
                    .optional();
            if (stopped.isEmpty()) {
                // Already denied, used, or expired while never approved: nothing is left to tidy.
                return true;
            }
            Stopped row = stopped.get();
            // **The cancellation lost the race, and that login must not stay behind.** An approval that
            // landed just before Ctrl-C wrote an authorization whose only collector has now exited.
            // The `approved_at IS NOT NULL` half of the condition above is what lets this run even
            // when the question's own clock has already run out — the CLI's clock starts at the answer
            // to `start`, a round trip earlier than the server's.
            if (row.approvedAt() != null && row.authorizationId() != null) {
                authorizations.revokeAuthorization(row.authorizationId(), Timestamps.now(),
                        "cli_sign_in_cancelled");
            }
            return true;
        }));
    }

    @Override
    public Optional<Poll> poll(String ticket, String deviceSecret, String codeVerifier,
            String callerAddress) {
        // **Counted first, before the row is touched.** Nothing here is worth rate limiting except the
        // work itself, and a request already refused should not also spend anything.
        count(authThrottle.countDeviceSignInPoll(callerAddress));
        if (ticket == null || ticket.isBlank() || deviceSecret == null || deviceSecret.isBlank()
                || codeVerifier == null || codeVerifier.isBlank()) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        // **One statement, so the interval is atomic rather than intended.** The row is allowed through
        // only when its last poll is old enough; two polls racing cannot both see it as old enough.
        Optional<Question> question = jdbc.sql("UPDATE cli_device_sign_in SET last_polled_at = :now"
                        + " WHERE ticket_hash = :ticket AND device_secret_hash = :secret"
                        + " AND (last_polled_at IS NULL OR last_polled_at <= :earliest)"
                        + " RETURNING client_id, scopes, code_challenge, name, address, created_at,"
                        + " expires_at, last_polled_at, approved_at, authorization_id, denied_at,"
                        + " consumed_at")
                .param("now", POLL_STAMP.format(now))
                .param("ticket", hashOf(ticket))
                .param("secret", hashOf(deviceSecret))
                .param("earliest", POLL_STAMP.format(now.minus(POLL_INTERVAL)))
                .query(PgCliSignIns::readQuestion)
                .optional();
        if (question.isEmpty()) {
            // Either the caller is not the process that asked, or it asked again too soon. The first
            // is the same answer as "no such ticket"; the second is a wait, not a failure.
            if (rowOf(ticket).filter(row -> row.secretHash().equals(hashOf(deviceSecret))).isPresent()) {
                throw new ThrottledException(POLL_INTERVAL.toSeconds());
            }
            return Optional.empty();
        }

        Question row = question.get();
        // **The verifier is checked before the status is reported, not after.** It is the caller's
        // proof that this is the process that asked; checked only when approved, every earlier answer
        // would be a status oracle for whoever holds a leaked device secret. A wrong verifier is the
        // same answer as a wrong ticket — one interval spent, nothing learned.
        if (!challengeOf(codeVerifier).equals(row.challenge())) {
            return Optional.empty();
        }
        String status = statusOf(row, now);
        if (!CliSignIn.APPROVED.equals(status)) {
            return Optional.of(new Poll(status, null));
        }
        return Optional.of(claimAndMint(ticket, row.authorizationId()));

    }

    /**
     * The claim and the credential, in one transaction.
     *
     * <p><strong>Why they share one.</strong> The claim is what makes a credential collectable once.
     * If it committed and minting then failed (a transient write error, a lookup that throws), the
     * person would be left with an approved login nobody can ever collect: every retry would answer
     * "used", and the only way out would be another `skmr login`. Rolling the claim back with the
     * failure makes the next poll the same poll again.
     */
    private Poll claimAndMint(String ticket, String authorizationId) {
        Poll answer = transactions.execute(status -> {
            // **The claim is what makes "once" true.** Two collectors racing cannot both consume it;
            // the loser is answered like a link that was already used.
            int claimed = jdbc.sql("UPDATE cli_device_sign_in SET consumed_at = :now"
                            + " WHERE ticket_hash = :ticket AND approved_at IS NOT NULL"
                            + " AND consumed_at IS NULL")
                    .param("now", Timestamps.now())
                    .param("ticket", hashOf(ticket))
                    .update();
            if (claimed == 0) {
                return new Poll(CliSignIn.USED, null);
            }
            return mint(authorizationId)
                    .map(credential -> new Poll(CliSignIn.APPROVED, credential))
                    .orElseGet(() -> new Poll(CliSignIn.USED, null));
        });
        return answer == null ? new Poll(CliSignIn.USED, null) : answer;
    }

    /**
     * The credential, minted once, from an authorization the person approved.
     *
     * <p>Empty when the authorization is gone or already ended — a login that was revoked between the
     * approval and the collection is not a login the CLI may have.
     */
    private Optional<Credential> mint(String authorizationId) {
        OAuth2Authorization authorization = authorizations.findById(authorizationId);
        if (authorization == null || authorizations.authorizationRevoked(authorizationId)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        String value = credentials.generateKey();
        Set<String> scopes = authorization.getAuthorizedScopes();
        String name = labelOf(authorizationId);
        // **The idle ruler, set at issuance.** A fresh login gets the full window — which is also why
        // the first request after a login never rewrites the row (CliSignInPolicy.stale).
        Instant expiresAt = cliSignInPolicy.slidExpiry(now, now);
        authorizations.save(OAuth2Authorization.from(authorization)
                .token(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, value, now, expiresAt,
                        scopes))
                .build());
        return Optional.of(new Credential(authorization.getPrincipalName(), name, value, expiresAt,
                scopes));
    }

    private void writeAuthorization(String userId, RegisteredClient client, Set<String> scopes,
            String name, String approvedFrom, String authorizationId, Instant now) {
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
        jdbc.sql("UPDATE oauth_authorization SET label = :label, address = :address WHERE id = :id")
                .param("label", name)
                .param("address", approvedFrom)
                .param("id", authorizationId)
                .update();
    }

    /**
     * The machine's name, off the authorization row — the value the approval wrote and the console
     * shows. The row is the one the question named, so a name from anywhere else would be a second
     * answer to "what did the person call this machine".
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
        // "later than now" is the whole of "this login can still be used".
        //
        // **The token's existence is required, and that is deliberate.** An authorization whose
        // question was never collected has no credential at all — the CLI died in between — so there
        // is nothing that could act as this person and nothing to end.
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
    public void revokeSelf(String userId, String authorizationId) {
        // The authorization id came off the caller's own token row, and that row's user is the one
        // the validator checked. The ownership test is still here rather than assumed: it is one
        // indexed lookup, and an id that reached here from anywhere else must not end a stranger's
        // login. A miss is a no-op — the credential was just found live, so it cannot happen.
        Optional<String> mine = jdbc.sql("SELECT id FROM oauth_authorization"
                        + " WHERE id = :id AND principal_name = :user")
                .param("id", authorizationId)
                .param("user", userId)
                .query(String.class)
                .optional();
        if (mine.isEmpty()) {
            return;
        }
        authorizations.revokeAuthorization(authorizationId, Timestamps.now(), "cli");
    }

    /**
     * Ends the login this one replaces: the same person, the same machine name, still live (Q7).
     *
     * <p><strong>The name is the only thing the server can recognise a machine by, and that has a
     * cost worth stating.</strong> The CLI knows which credential it is holding; the server does not,
     * and ADR 0040 §决定 4 keeps the CLI out of revocation. So "the same machine" is resolved from
     * what the person typed, and two machines given the same name end each other.
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

    private RegisteredClient clientOrThrow(String clientId) {
        RegisteredClient client = clients.findByClientId(clientId);
        if (client == null) {
            // Loud, and naming the row to seed, in the shape ClientRegistry already uses for a
            // mis-seeded deployment: a login that cannot be started is a deployment fact, not a
            // request the caller got wrong.
            throw new IllegalStateException("no oauth_client row for '" + clientId
                    + "' — this deployment cannot start a login. Seed it (see the server README).");
        }
        return client;
    }

    private Optional<Row> rowOf(String ticket) {
        return jdbc.sql("SELECT ticket_hash, device_secret_hash, client_id, scopes, code_challenge,"
                        + " name, address, created_at, expires_at, last_polled_at, approved_at,"
                        + " authorization_id, denied_at, consumed_at"
                        + " FROM cli_device_sign_in WHERE ticket_hash = :ticket")
                .param("ticket", hashOf(ticket))
                .query(PgCliSignIns::readRow)
                .optional();
    }

    private static Row readRow(ResultSet rs, int rowNumber) throws SQLException {
        return new Row(rs.getString("ticket_hash"), rs.getString("device_secret_hash"),
                rs.getString("client_id"), rs.getString("scopes"), rs.getString("code_challenge"),
                rs.getString("name"), rs.getString("address"),
                Timestamps.parse(rs.getString("created_at")),
                Timestamps.parse(rs.getString("expires_at")),
                instantOrNull(rs, "approved_at"), rs.getString("authorization_id"),
                instantOrNull(rs, "denied_at"), instantOrNull(rs, "consumed_at"));
    }

    private static Question readQuestion(ResultSet rs, int rowNumber) throws SQLException {
        return new Question(rs.getString("client_id"), rs.getString("scopes"),
                rs.getString("code_challenge"), rs.getString("name"), rs.getString("address"),
                Timestamps.parse(rs.getString("created_at")),
                Timestamps.parse(rs.getString("expires_at")),
                instantOrNull(rs, "approved_at"), rs.getString("authorization_id"),
                instantOrNull(rs, "denied_at"), instantOrNull(rs, "consumed_at"));
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Timestamps.parse(value);
    }

    /**
     * How long ago the question was asked, as this server counted it.
     *
     * <p><strong>The server counts, not the page.</strong> The line exists so a person can tell whether
     * the request came from the machine they just ran the command on, and a browser whose clock is
     * hours ahead would turn a just-asked question into 「120 分钟前发起」 on a link that lives ten.
     * Clamped at zero for the same class of skew in the other direction.
     */
    private static long ageMinutesOf(Instant createdAt, Instant now) {
        return Math.max(0, Duration.between(createdAt, now).toMinutes());
    }

    private static String statusOf(Row row, Instant now) {
        return statusOf(row.consumedAt(), row.deniedAt(), row.approvedAt(), row.expiresAt(), now);
    }

    private static String statusOf(Question row, Instant now) {
        return statusOf(row.consumedAt(), row.deniedAt(), row.approvedAt(), row.expiresAt(), now);
    }

    private static String statusOf(Instant consumedAt, Instant deniedAt, Instant approvedAt,
            Instant expiresAt, Instant now) {
        if (consumedAt != null) {
            return CliSignIn.USED;
        }
        if (deniedAt != null) {
            return CliSignIn.DENIED;
        }
        // **An approval that happened in time stays collectable after the question's clock runs out.**
        // The ten minutes bound the person's decision, not the machine's pickup: the CLI asks once a
        // second, so without this ordering an approval clicked in the last second would be answered
        // as expired forever, with the authorization row already written and no credential behind it.
        if (approvedAt != null) {
            return CliSignIn.APPROVED;
        }
        if (!expiresAt.isAfter(now)) {
            return CliSignIn.EXPIRED;
        }
        return CliSignIn.PENDING;
    }

    /** Never widens: what the caller asked for, limited to what the client is registered for. */
    private static Set<String> intersect(Set<String> asked, Set<String> registered) {
        Set<String> kept = new LinkedHashSet<>(asked);
        kept.retainAll(registered);
        return Set.copyOf(kept);
    }

    private static Set<String> scopesOf(String stored) {
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : stored.split(" ")) {
            if (!scope.isBlank()) {
                scopes.add(scope);
            }
        }
        return Set.copyOf(scopes);
    }

    private static void count(Throttle throttle) {
        if (throttle instanceof Throttle.Refused refused) {
            throw new ThrottledException(refused.retryAfterSeconds());
        }
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

    /** The row a ticket names, with every column the page and the poll read. */
    /** What a cancellation's RETURNING reports about the row it just answered. */
    /**
     * The stamp the poll interval is measured with, at millisecond precision.
     *
     * <p><strong>Whole seconds were too coarse to be a one-second interval.</strong> Two polls in
     * adjacent seconds are always "one second apart" when the stored value has no fraction, so a
     * request landing three milliseconds after one in the previous second was let through. Fixed
     * width and always UTC, so the text comparison in SQL stays a comparison of time.
     */
    private static final DateTimeFormatter POLL_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private record Stopped(String approvedAt, String authorizationId) {
    }

    private record Row(String ticketHash, String secretHash, String clientId, String scopes,
            String challenge, String name, String address, Instant createdAt, Instant expiresAt,
            Instant approvedAt, String authorizationId, Instant deniedAt, Instant consumedAt) {
    }

    /** The subset a poll needs, read by the statement that also spends the interval. */
    private record Question(String clientId, String scopes, String challenge, String name,
            String address, Instant createdAt, Instant expiresAt, Instant approvedAt,
            String authorizationId, Instant deniedAt, Instant consumedAt) {
    }

    /** What the approval's claim returns: enough to write the authorization without a second read. */
    private record Claimed(String clientId, String scopes) {
    }
}
