package com.skillmasterai.modules.token.internal;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.token.ApiKey;
import com.skillmasterai.modules.token.ApiKeyFormat;
import com.skillmasterai.modules.token.ApiKeys;
import com.skillmasterai.modules.token.IssuedApiKey;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code api_key} rows — the credential an account issues to itself, under the same "hash and never
 * the value" rule every other credential here follows (ADR 0007).
 *
 * <p><strong>What is stored is the sha256 of the whole presented string, prefix included</strong>,
 * so the validator can hash what it was handed and ask for that. The plaintext exists in the
 * response that created it and nowhere else, which is what makes a database leak not a credential
 * leak rather than merely a smaller one.
 *
 * <p><strong>Revocation is a timestamp and a renewal is an insert beside it.</strong> Nothing here
 * deletes a row: the listing has to be able to show a key that was revoked, and "when did it stop
 * working" is the half of that somebody actually needs. It is also why a renewal copies the row
 * instead of editing it — the new credential's history is not the old one's.
 *
 * <p><strong>A renewal writes one audit row, not two.</strong> The revocation and the issuance are
 * two statements, but they are one action the person took, and a trail that spells it as a
 * revocation followed by an issuance makes an ordinary renewal look like somebody shutting a key
 * off. The audit rows are written here, beside the statements they describe, because inside the same
 * transaction they cannot disagree with what happened ({@link AuditLog}).
 */
public final class ApiKeyRegistry implements ApiKeys {

    private static final String COLUMNS =
            "id, name, note, scope, expires_at, last_used_at, revoked_at, created_at";

    private final JdbcClient jdbc;
    private final AuditLog audit;

    public ApiKeyRegistry(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Override
    public IssuedApiKey issue(String userId, String name, String note, Set<String> scopes,
            Instant expiresAt) {
        IssuedApiKey issued = insert(userId, name, note, joined(scopes), expiresAt);
        audit.record(new AuditEvent(userId, "api_key_issue", "api_key", issued.key().id(),
                Map.of("name", name, "scope", issued.key().scope(),
                        "expires_at", neverIfNull(issued.key().expiresAt()))));
        return issued;
    }

    @Override
    public List<ApiKey> listOf(String userId) {
        // Newest first, which is the order a person reads them in: the one they just made is the one
        // they are looking for. The id breaks ties, so two keys created in the same second cannot
        // swap places between two calls.
        return jdbc.sql("SELECT " + COLUMNS + " FROM api_key WHERE user_id = :userId"
                        + " ORDER BY created_at DESC, id DESC")
                .param("userId", userId)
                .query(ApiKeyRegistry::read)
                .list();
    }

    @Override
    public Optional<ApiKey> find(String userId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM api_key"
                        + " WHERE id = :id AND user_id = :userId")
                .param("id", id)
                .param("userId", userId)
                .query(ApiKeyRegistry::read)
                .optional();
    }

    @Override
    public void revoke(String userId, String id) {
        if (markRevoked(userId, id)) {
            audit.record(AuditEvent.of(userId, "api_key_revoke", "api_key", id));
        }
    }

    @Override
    public Optional<IssuedApiKey> rotate(String userId, String id, Instant expiresAt) {
        Optional<ApiKey> live = liveRow(userId, id);
        if (live.isEmpty()) {
            return Optional.empty();
        }
        // **Whoever stops the old key is the one that issues the new one**, and the conditional
        // update is what decides it. Two requests can reach here at once — a double click on the
        // console's confirm, a retried POST — and both will have read a live row; the second
        // `markRevoked` matches nothing once the first has committed. Without this check the second
        // one carried on to `insert` anyway, and the account ended up with **two live keys**, only
        // one of which the person ever saw: precisely the overlap the renewal contract forbids
        // ("先用旧的一把停住，再发新的；两者不重叠" — technical-design §接口).
        if (!markRevoked(userId, id)) {
            return Optional.empty();
        }
        ApiKey old = live.get();
        IssuedApiKey renewed = insert(userId, old.name(), old.note(), old.scope(), expiresAt);
        audit.record(new AuditEvent(userId, "api_key_rotate", "api_key", renewed.key().id(),
                Map.of("replaced", id,
                        "name", old.name(),
                        "scope", old.scope(),
                        "expires_at", neverIfNull(renewed.key().expiresAt()))));
        return Optional.of(renewed);
    }

    /**
     * Stops a key working, without saying so in the audit trail.
     *
     * <p>Conditional on {@code revoked_at IS NULL} rather than read-then-write: that column is what
     * the listing shows as "when it stopped working", and a second click must not move it.
     *
     * @return whether a live key was actually stopped
     */
    private boolean markRevoked(String userId, String id) {
        return jdbc.sql("UPDATE api_key SET revoked_at = :now"
                        + " WHERE id = :id AND user_id = :userId AND revoked_at IS NULL")
                .param("now", Timestamps.now())
                .param("id", id)
                .param("userId", userId)
                .update() > 0;
    }

    /** The row behind a key that still works, or empty — including when it never existed. */
    private Optional<ApiKey> liveRow(String userId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM api_key"
                        + " WHERE id = :id AND user_id = :userId AND revoked_at IS NULL")
                .param("id", id)
                .param("userId", userId)
                .query(ApiKeyRegistry::read)
                .optional();
    }

    private IssuedApiKey insert(String userId, String name, String note, String scope,
            Instant expiresAt) {
        String token = ApiKeyFormat.generate();
        String id = Ulid.generate();
        String createdAt = Timestamps.now();
        String expiry = expiresAt == null ? null : Timestamps.format(expiresAt);

        jdbc.sql("INSERT INTO api_key (id, user_id, name, note, token_hash, scope, expires_at,"
                        + " created_at) VALUES (:id, :userId, :name, :note, :tokenHash, :scope,"
                        + " :expiresAt, :createdAt)")
                .param("id", id)
                .param("userId", userId)
                .param("name", name)
                .param("note", note)
                // The hash of the whole string, prefix included: exactly what the validator will
                // compute from what it is handed. No other form of the secret reaches this table.
                .param("tokenHash", Sha256Hex.of(token.getBytes(StandardCharsets.UTF_8)))
                .param("scope", scope)
                .param("expiresAt", expiry)
                .param("createdAt", createdAt)
                .update();

        return new IssuedApiKey(new ApiKey(id, name, note, scope, expiry, null, null, createdAt),
                token);
    }

    /**
     * The scope spelling the column holds: sorted, so two keys granted the same permissions are
     * written the same way. {@code Set} iteration order is not promised, and a listing that showed
     * {@code skills:read skills:write} one day and the reverse the next would be a difference nobody
     * made.
     */
    private static String joined(Set<String> scopes) {
        return scopes.stream().sorted().collect(Collectors.joining(" "));
    }

    /** The audit detail says "never" in words: JSON has a null, but a reader should not have to. */
    private static String neverIfNull(String expiry) {
        return expiry == null ? "never" : expiry;
    }

    private static ApiKey read(ResultSet rs, int rowNum) throws SQLException {
        return new ApiKey(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("note"),
                rs.getString("scope"),
                rs.getString("expires_at"),
                rs.getString("last_used_at"),
                rs.getString("revoked_at"),
                rs.getString("created_at"));
    }
}
