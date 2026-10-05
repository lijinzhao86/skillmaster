package com.skillmasterai.modules.token.internal;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.auth.TokenValidator;
import com.skillmasterai.modules.token.TokenAudience;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Turns a bearer token this service issued into a subject — the second implementation of M3's
 * seam, and the one P1 was for.
 *
 * <p><strong>The lookup is by hash, and the presented token is never stored or logged.</strong> The
 * table holds sha256 and nothing else (ADR 0007, §3.1 constraint 1), so this hashes what it was
 * given and asks for that. It is the same property that makes a database leak not a token leak.
 *
 * <p><strong>Three things are checked, in one query.</strong> Not revoked, not expired, and minted
 * for this service — that last one is the audience (ADR 0021, §3.1 constraint 2), and it is a
 * different question from the other two: "this token is genuine" and "this token is genuine *for
 * here*" are only the same question while there is one resource. They stop being the same the day
 * there are two, and the check costs nothing now.
 *
 * <p><strong>It is one query and no cache.</strong> A cache would be a revocation that takes effect
 * eventually, which is precisely what ADR 0021 declined to buy when it chose opaque tokens over
 * JWT — the whole reason for the round trip is that "revoked" has to mean "revoked now".
 *
 * <p>It answers an empty {@link Optional} for every kind of no: the interface requires that the
 * caller cannot tell "no such token" from "expired" or "revoked", because that distinction is a
 * question an unauthenticated caller has no business asking.
 */
public final class IssuedTokenValidator implements TokenValidator {

    private final JdbcClient jdbc;
    private final TokenAudience audience;

    public IssuedTokenValidator(JdbcClient jdbc, TokenAudience audience) {
        this.jdbc = jdbc;
        this.audience = audience;
    }

    @Override
    public Optional<AuthenticatedSubject> validate(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return Optional.empty();
        }
        String hash = Sha256Hex.of(presentedToken.getBytes(StandardCharsets.UTF_8));

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
                .query((rs, rowNum) -> new AuthenticatedSubject(
                        rs.getString("user_id"), scopesOf(rs.getString("scope"))))
                .optional();
    }

    private static Set<String> scopesOf(String spaceSeparated) {
        return spaceSeparated.isBlank()
                ? Set.of()
                : new LinkedHashSet<>(List.of(spaceSeparated.split(" ")));
    }
}
