package com.skillmasterai.modules.token.internal;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.auth.TokenValidation;
import com.skillmasterai.modules.auth.TokenValidator;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.token.ApiKeyFormat;
import com.skillmasterai.modules.token.ApiKeyPolicy;
import com.skillmasterai.modules.token.CliSignInPolicy;
import com.skillmasterai.modules.token.TokenAudience;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Turns a bearer token this service issued into a subject — M3's seam, implemented here, and the one
 * place that decides what a credential is and why it is not usable.
 *
 * <p><strong>The lookup is by hash, and the presented token is never stored or logged.</strong> Both
 * tables hold sha256 and nothing else (ADR 0007, §3.1 constraint 1), so this hashes what it was given
 * and asks for that. It is the same property that makes a database leak not a credential leak.
 *
 * <p><strong>Two credentials, one seam, and the prefix is what tells them apart.</strong> A key
 * begins with {@code skm_} and an OAuth access token cannot (ADR 0038 §决定 2), so the table to look
 * in is decided without a second query. That is routing and not judgement: a string that takes the
 * wrong route is a miss, not an acceptance, because what decides is still the hash lookup.
 *
 * <p><strong>The key's liveness is judged in Java rather than in the {@code WHERE} clause, and that
 * is the price of saying why.</strong> A query that filters out revoked, expired and suspended rows
 * can only answer "usable" or "nothing", and the design needs those told apart: an expired key is
 * renewed and a revoked one is replaced, so the two lead a person to different places (ADR 0039
 * §决定 3). The cost is real and is paid here — there is no SQL predicate behind these three
 * {@code if}s, so a missing one is a dead key that works, and each has an integration test rather
 * than a careful reading.
 *
 * <p><strong>What a refusal is allowed to say.</strong> "This string matches nothing" and "no
 * credential was presented" stay indistinguishable: both are {@code unauthenticated}, which is what
 * this path answered before reasons existed. The named reasons are only produced after a row was
 * found by its hash, and finding one requires holding 256 bits of secret — see {@link TokenValidation}.
 *
 * <p><strong>OAuth tokens now answer the same way, and v4 is why.</strong> Until then that path
 * filtered in SQL and every refusal was {@code unauthenticated}, on the reasoning that a credential
 * with no clients (ADR 0041 §决定 3) did not deserve finer wording. v4 gave it a client — {@code
 * skmr login} now mints one of these (ADR 0028's fixed loopback port is where it lands) — and
 * a person has to be told which of its two rulers ran out, because the two lead somewhere different.
 * So the path is restructured to judge in Java, exactly as the key path is, and both credentials now
 * answer out of one vocabulary.
 *
 * <p><strong>It is one query and no cache</strong>, for the access-token path as before: a cache
 * would be a revocation that takes effect eventually, which is precisely what ADR 0021 declined to
 * buy when it chose opaque tokens over JWT.
 */
public final class IssuedTokenValidator implements TokenValidator {

    private final JdbcClient jdbc;
    private final TokenAudience audience;
    private final ApiKeyPolicy keyPolicy;
    private final CliSignInPolicy cliSignInPolicy;
    private final AuditLog audit;
    private final AccountDirectory accounts;

    public IssuedTokenValidator(JdbcClient jdbc, TokenAudience audience, ApiKeyPolicy keyPolicy,
            CliSignInPolicy cliSignInPolicy, AuditLog audit, AccountDirectory accounts) {
        this.jdbc = jdbc;
        this.audience = audience;
        this.keyPolicy = keyPolicy;
        this.cliSignInPolicy = cliSignInPolicy;
        this.audit = audit;
        this.accounts = accounts;
    }

    @Override
    public TokenValidation validate(String presentedToken, String callerAddress) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return rejected();
        }
        String hash = Sha256Hex.of(presentedToken.getBytes(StandardCharsets.UTF_8));
        return ApiKeyFormat.routesToApiKeys(presentedToken)
                ? validateApiKey(hash, callerAddress)
                : validateIssuedToken(hash);
    }

    private TokenValidation validateApiKey(String hash, String callerAddress) {
        Optional<ApiKeyRow> found = jdbc.sql("SELECT k.id, k.user_id, k.scope, k.expires_at,"
                        + " k.revoked_at, k.last_used_from"
                        + " FROM api_key k WHERE k.token_hash = :hash")
                .param("hash", hash)
                .query(IssuedTokenValidator::readApiKey)
                .optional();
        if (found.isEmpty()) {
            return rejected();
        }

        ApiKeyRow key = found.get();
        // The order is the design's, and it is not alphabetical: a deliberate act is reported before
        // the passage of time. Revoking and suspending are things somebody did, and each sends the
        // person somewhere different; "expired" is the one that was going to happen anyway.
        if (key.revokedAt() != null) {
            // ADR 0039 §决定 4's second signal: a key that was revoked has turned up again. Worth a
            // row every time it happens — a flood of them is itself the finding — and the address is
            // what makes the row useful, since the interesting case is a machine that kept a copy.
            audit.record(new AuditEvent(key.userId(), "api_key_revoked_reuse", "api_key", key.id(),
                    detail(callerAddress)));
            return new TokenValidation.Rejected(ErrorCode.TOKEN_REVOKED);
        }
        // Asked of M1 rather than read out of its table: §2.5 rule 1 keeps a module out of another's
        // tables, and TableOwnershipTest fails the build over a query that forgets it. A second query
        // where the rest of this path is one — and it is only reached by a credential that has already
        // been found, so a guess never gets this far.
        if (!accounts.isActive(key.userId())) {
            return new TokenValidation.Rejected(ErrorCode.ACCOUNT_SUSPENDED);
        }
        if (hasPassed(key.expiresAt())) {
            return new TokenValidation.Rejected(ErrorCode.TOKEN_EXPIRED);
        }

        noteUse(key, callerAddress);
        return new TokenValidation.Accepted(
                AuthenticatedSubject.ofApiKey(key.userId(), scopesOf(key.scope()), key.id()));
    }

    /**
     * The login-credential path: judged in Java, because a person has to be told which ruler ran out.
     *
     * <p><strong>The parent authorization is read as a value, not tested with {@code EXISTS}.</strong>
     * Ending a login in the console marks the authorization, and (V9) a refresh already in flight can
     * still write a token row the mark never saw — so the parent's state has to be part of this
     * decision either way. It used to be an {@code EXISTS} in the {@code WHERE} clause, which can only
     * answer yes or no; a person whose login was ended needs that said. Joining on the primary key is
     * at least as strong: V6's foreign key guarantees one parent, and a token whose parent is missing
     * yields no row at all.
     *
     * <p><strong>The audience is checked here rather than in SQL, and stays the silent refusal it
     * always was.</strong> A token minted for another service is not ours; telling its holder about
     * this account would report a fact that has nothing to do with what was presented.
     */
    private TokenValidation validateIssuedToken(String hash) {
        Optional<AccessTokenRow> found = jdbc.sql("SELECT t.user_id, t.scope, t.audience,"
                        + " t.expires_at, t.revoked_at, t.created_at,"
                        + " a.revoked_at AS authorization_revoked_at, a.label"
                        + " FROM access_token t"
                        + " JOIN oauth_authorization a ON a.id = t.authorization_id"
                        + " WHERE t.token_hash = :hash")
                .param("hash", hash)
                .query(IssuedTokenValidator::readAccessToken)
                .optional();
        if (found.isEmpty()) {
            return rejected();
        }

        AccessTokenRow row = found.get();
        if (!audience.value().equals(row.audience())) {
            return rejected();
        }
        // The key path's order, and for the key path's reason: a deliberate act is reported before the
        // passage of time. Either row being marked is revocation — the console marks the
        // authorization, and the row itself is marked when one of its tokens is invalidated.
        if (row.revokedAt() != null || row.authorizationRevokedAt() != null) {
            return new TokenValidation.Rejected(ErrorCode.TOKEN_REVOKED);
        }
        // The same rule the key path applies, and for the same reason: a suspended account's
        // credentials stop working on the next request rather than at their next expiry.
        if (!accounts.isActive(row.userId())) {
            return new TokenValidation.Rejected(ErrorCode.ACCOUNT_SUSPENDED);
        }

        Instant now = Instant.now();
        Instant created = Timestamps.parse(row.createdAt());
        if (!Timestamps.parse(row.expiresAt()).isAfter(now)) {
            // **Which ruler ran out, and only a credential that has two of them can be asked.** The
            // expiry slides, so both deaths look identical in the column; what separates them is
            // whether issuance is past the absolute ceiling — see CliSignInPolicy. But that question
            // only means something for the credentials this version issues, which is the same set the
            // slide applies to and the same marker: a third-party token has a one-hour lifetime and a
            // refresh chain behind it, so it has no idleness rule at all and every one of its expiries
            // is simply an expiry. Asking the ceiling question of one would answer `token_idle` for
            // every third-party token that ever ran out — a ruler that shape does not have.
            boolean hasRulers = row.label() != null;
            return new TokenValidation.Rejected(
                    hasRulers && !cliSignInPolicy.pastAbsoluteCeiling(created, now)
                            ? ErrorCode.TOKEN_IDLE
                            : ErrorCode.TOKEN_EXPIRED);
        }

        // **Only the credentials this version issues slide, and the label is the marker.** Both rulers
        // belong to the CLI login (v4 Q1, Q6). A third-party access token has a one-hour lifetime with
        // a refresh chain behind it, and sliding its expiry out to a day would make a stolen one worth
        // twenty-four times more — on a path v4 explicitly did not touch (Q4). The label is the honest
        // marker rather than the client id: it is written by, and only by, the sign-in this version
        // added, so "rows with rulers" and "rows with a label" are the same set by construction instead
        // of by two strings that could drift apart.
        if (row.label() != null) {
            slide(hash, row, created, now);
        }
        return new TokenValidation.Accepted(
                AuthenticatedSubject.ofOAuthToken(row.userId(), scopesOf(row.scope())));
    }

    /**
     * Pushes the expiry forward on a use — the whole of "idle 24 hours, and no new column to say so".
     *
     * <p><strong>Bookkeeping, never an authority.</strong> It runs only after every liveness judgement
     * has already passed, and it carries {@code revoked_at IS NULL} of its own, so it can neither
     * revive a credential nor slide one back to life that the checks above just refused.
     *
     * <p><strong>{@code expires_at < :target} earns its place twice.</strong> It makes "a use never
     * shortens a life" true rather than merely intended, and it is what stops a credential that has
     * reached its ceiling from being rewritten on every sampled request for the rest of its life: at
     * the ceiling the target equals the stored value, the statement matches nothing, and no row is
     * written.
     *
     * <p><strong>The condition lives in the {@code WHERE} clause rather than in a read-then-write</strong>,
     * the same shape {@link #noteUse} uses for a key: inside the window the row is not touched at all,
     * which is what keeps this path as close to read-only as a sliding expiry allows. A read that
     * stamps is already accepted here — {@code noteUse} does it for every key in use, and
     * {@code SessionAbsoluteLimitFilter} records the same cost on the browser plane.
     */
    private void slide(String hash, AccessTokenRow row, Instant created, Instant now) {
        if (!cliSignInPolicy.stale(Timestamps.parse(row.expiresAt()), now)) {
            return;
        }
        jdbc.sql("UPDATE access_token SET expires_at = :target"
                        + " WHERE token_hash = :hash AND revoked_at IS NULL"
                        + " AND expires_at < :target")
                .param("target", Timestamps.format(cliSignInPolicy.slidExpiry(created, now)))
                .param("hash", hash)
                .update();
        // The row's own last-use stamp, in one statement with the expiry it explains (V17). It is here
        // rather than derived from the expiry because derivation breaks exactly where the ceiling binds
        // — the final stretch of a login's life, which is when the console's 「最近活动」 is worth
        // reading. Written only when the slide wrote: the sampling window is the same window, which is
        // what keeps a busy machine from costing a write per request.
        jdbc.sql("UPDATE oauth_authorization a SET last_used_at = :now"
                        + " FROM access_token t"
                        + " WHERE t.token_hash = :hash AND a.id = t.authorization_id")
                .param("now", Timestamps.format(now))
                .param("hash", hash)
                .update();
    }

    /**
     * Records that the key was used: the throttled stamp, and the first use from somewhere new.
     *
     * <p><strong>The write is conditional in its {@code WHERE} clause rather than read-then-write</strong>,
     * so a key in constant use is "almost no request writes" rather than "every request writes and
     * then rolls back". The same condition is what makes the address signal sampled: the address is
     * only compared when the window has passed, which keeps a signal-per-window instead of a
     * signal-per-request for a machine that is hammering the API from one new place.
     *
     * @param callerAddress may be null where there is no socket behind the call; the stamp is still
     *                      written, and the address signal is simply not available
     */
    private void noteUse(ApiKeyRow key, String callerAddress) {
        String now = Timestamps.now();
        String staleBefore = Timestamps.format(Instant.now().minus(keyPolicy.lastUsedWindow()));

        int written = jdbc.sql("UPDATE api_key SET last_used_at = :now, last_used_from = :address"
                        + " WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :staleBefore)")
                .param("now", now)
                .param("address", callerAddress)
                .param("id", key.id())
                .param("staleBefore", staleBefore)
                .update();

        // Only when the stamp actually moved: within the window the stored address is still the old
        // one, and auditing on every request would report the same new address until the window
        // turned over — a signal that repeats is a signal nobody reads.
        if (written > 0 && key.lastUsedFrom() != null && callerAddress != null
                && !key.lastUsedFrom().equals(callerAddress)) {
            audit.record(new AuditEvent(key.userId(), "api_key_new_address", "api_key", key.id(),
                    Map.of("previous", key.lastUsedFrom(), "current", callerAddress)));
        }
    }

    /** NULL means the key does not expire, which is a state the column is able to express. */
    private static boolean hasPassed(String expiresAt) {
        return expiresAt != null && expiresAt.compareTo(Timestamps.now()) <= 0;
    }

    private static TokenValidation rejected() {
        return new TokenValidation.Rejected(ErrorCode.UNAUTHENTICATED);
    }

    /** The audit detail omits an address it does not have rather than storing a null. */
    private static Map<String, Object> detail(String callerAddress) {
        return callerAddress == null ? Map.of() : Map.of("from", callerAddress);
    }

    private static Set<String> scopesOf(String spaceSeparated) {
        return spaceSeparated.isBlank()
                ? Set.of()
                : new LinkedHashSet<>(List.of(spaceSeparated.split(" ")));
    }

    private static AccessTokenRow readAccessToken(ResultSet rs, int rowNum) throws SQLException {
        return new AccessTokenRow(
                rs.getString("user_id"),
                rs.getString("scope"),
                rs.getString("audience"),
                rs.getString("expires_at"),
                rs.getString("revoked_at"),
                rs.getString("created_at"),
                rs.getString("authorization_revoked_at"),
                rs.getString("label"));
    }

    private static ApiKeyRow readApiKey(ResultSet rs, int rowNum) throws SQLException {
        return new ApiKeyRow(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("scope"),
                rs.getString("expires_at"),
                rs.getString("revoked_at"),
                rs.getString("last_used_from"));
    }

    /**
     * The row the login path needs — which, since v4, is the token plus one thing about its parent.
     *
     * <p>{@code createdAt} is here because the absolute ceiling is not stored anywhere: it is this
     * plus the configured window, so the ceiling can only be applied by whoever holds both. And
     * {@code authorizationRevokedAt} is here because ending a login marks the parent rather than the
     * token, and a person whose login was ended has to be told that rather than "unauthenticated".
     *
     * <p>{@code expiresAt} and {@code revokedAt} are read as the strings the database holds, not as
     * Instants: they are compared lexicographically, which is chronological because every timestamp
     * here is RFC3339 UTC truncated to whole seconds — the property {@code Timestamps} exists to keep.
     *
     * <p>{@code label} is here to answer one question and no others: is this a credential this version
     * issued, and therefore one the rulers apply to? See where it is used.
     */
    private record AccessTokenRow(
            String userId,
            String scope,
            String audience,
            String expiresAt,
            String revokedAt,
            String createdAt,
            String authorizationRevokedAt,
            String label) {
    }

    /**
     * The row this path needs, and nothing the request does not.
     *
     * <p>Not {@code ApiKey}: that one is the console's view and carries the name, the note and the
     * created date, none of which a request has any use for. Selecting them would be reading a
     * person's own words into the request path for no reason.
     */
    private record ApiKeyRow(
            String id,
            String userId,
            String scope,
            String expiresAt,
            String revokedAt,
            String lastUsedFrom) {
    }
}
