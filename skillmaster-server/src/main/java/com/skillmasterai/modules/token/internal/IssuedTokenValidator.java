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
 * <p><strong>OAuth tokens keep the old single answer.</strong> Their query is left filtering in SQL,
 * so every refusal there is {@code unauthenticated}. Restructuring a working path to give finer
 * wording to a credential with no clients (ADR 0041 §决定 3) would be risk bought for nothing; when a
 * third-party host actually uses it, its own answers can be decided then.
 *
 * <p><strong>It is one query and no cache</strong>, for the access-token path as before: a cache
 * would be a revocation that takes effect eventually, which is precisely what ADR 0021 declined to
 * buy when it chose opaque tokens over JWT.
 */
public final class IssuedTokenValidator implements TokenValidator {

    private final JdbcClient jdbc;
    private final TokenAudience audience;
    private final ApiKeyPolicy keyPolicy;
    private final AuditLog audit;
    private final AccountDirectory accounts;

    public IssuedTokenValidator(JdbcClient jdbc, TokenAudience audience, ApiKeyPolicy keyPolicy,
            AuditLog audit, AccountDirectory accounts) {
        this.jdbc = jdbc;
        this.audience = audience;
        this.keyPolicy = keyPolicy;
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

    private TokenValidation validateIssuedToken(String hash) {
        return jdbc.sql("SELECT user_id, scope FROM access_token"
                        + " WHERE token_hash = :hash"
                        + " AND revoked_at IS NULL"
                        + " AND audience = :audience"
                        + " AND expires_at > :now"
                        // **The authorization has to be live too, and this is not belt and braces.**
                        // Revoking an authorization marks its rows, but a refresh already in flight can
                        // write a row the sweep never saw (V9) — and this is the one query that decides
                        // whether a token can act, so it is the one that must not be fooled by a
                        // leftover row. Same connection, same statement: no window between the two.
                        + " AND EXISTS (SELECT 1 FROM oauth_authorization a"
                        + " WHERE a.id = access_token.authorization_id AND a.revoked_at IS NULL)")
                .param("hash", hash)
                .param("audience", audience.value())
                // Lexicographic comparison, which is chronological because every timestamp here is
                // RFC3339 UTC truncated to whole seconds — the property Timestamps exists to keep.
                .param("now", Timestamps.now())
                .query((rs, rowNum) -> AuthenticatedSubject.ofOAuthToken(
                        rs.getString("user_id"), scopesOf(rs.getString("scope"))))
                .optional()
                // **And the account has to be live.** New in v2, and it is the same rule the key path
                // applies: a suspended account's credentials stop working on the next request rather
                // than at their next expiry. It was a registered debt before this; a 90-day credential
                // is what made it a requirement. Asked of M1 rather than joined out of its table (§2.5
                // rule 1), and it is a filter rather than a question with an answer because this path's
                // refusals are all the same one — see the class javadoc.
                .filter(row -> accounts.isActive(row.userId()))
                .<TokenValidation>map(TokenValidation.Accepted::new)
                .orElseGet(IssuedTokenValidator::rejected);
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
