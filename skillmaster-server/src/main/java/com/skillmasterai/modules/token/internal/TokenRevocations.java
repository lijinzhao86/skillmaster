package com.skillmasterai.modules.token.internal;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.token.TokenRevocation;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Ends every credential a user holds, by marking rows in the three token tables.
 *
 * <p><strong>A class of its own rather than a second interface on {@link TokenStore}.</strong> Both
 * would be the same object, and Spring cannot tell two beans of one type apart: the store already
 * answers {@code OAuth2AuthorizationService}, so declaring a second bean of it as
 * {@code TokenRevocation} makes "give me the authorization service" ambiguous and the context fails
 * to start. Splitting it is what makes each interface a bean of its own.
 *
 * <p>It shares no code with the store's per-authorization revocation because the two act on different
 * keys — one row's worth of authorization against everything a person holds — and the SQL that says
 * so is the clearest statement of the difference.
 *
 * <p>What it does <em>not</em> do is delete. Rows stay, with a revocation timestamp, so a token
 * presented afterwards is still findable and answers "revoked" rather than "unknown". That
 * distinction is what ADR 0024's grace window needs, and it is also what a support question about
 * whether a credential was cut off can be answered from.
 */
public final class TokenRevocations implements TokenRevocation {

    private final JdbcClient jdbc;
    private final AuditLog audit;

    public TokenRevocations(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Override
    public void revokeAllFor(String userId) {
        String now = Timestamps.now();
        // The authorizations first, so that the four statements agree about which of them is the
        // authority (V9) — and so that the autocommit callers, `POST /oauth/revoke` among them, end
        // the authorization before its tokens. Inside a transaction the order buys nothing (a reader
        // cannot see any of it until commit) and costs nothing, which is the right trade for a
        // statement sequence that has to be correct on both paths.
        //
        // **Two ways to be this person's authorization, because the two grants spell them
        // differently.** A browser authorization carries the userId as `principal_name`. An
        // unattended one does not: for `client_credentials` the framework's principal is the client
        // authentication, so the row carries the CLIENT id while its token rows carry the account the
        // client acts as (ADR 0022 — `subjectOf` in TokenStore exists for exactly this reason). Keyed
        // on `principal_name` alone, the sweep marked every token and left the parent — the flag V9
        // made the authority — alive, and reported `authorizations: 0` beside a non-zero token count.
        int authorizations = jdbc.sql(
                        "UPDATE oauth_authorization SET revoked_at = :now"
                                + " WHERE revoked_at IS NULL"
                                + " AND (principal_name = :id"
                                + " OR registered_client_id IN"
                                + " (SELECT client_id FROM oauth_client WHERE user_id = :id))")
                .param("now", now).param("id", userId).update();
        // Each statement touches only rows that are still live, so the four of them agree about
        // what "everything this person holds" means without a COALESCE anywhere.
        int codes = jdbc.sql(
                        "UPDATE auth_code SET used_at = :now WHERE user_id = :id AND used_at IS NULL")
                .param("now", now).param("id", userId).update();
        int access = jdbc.sql(
                        "UPDATE access_token SET revoked_at = :now WHERE user_id = :id"
                                + " AND revoked_at IS NULL")
                .param("now", now).param("id", userId).update();
        int refresh = jdbc.sql(
                        "UPDATE refresh_token SET revoked_at = :now WHERE user_id = :id"
                                + " AND revoked_at IS NULL")
                .param("now", now).param("id", userId).update();

        // The counts go in the detail because they are what somebody asking "was that credential
        // actually cut off" wants, and because a zero here is worth being able to see: it says the
        // user had nothing live, which is a different fact from the revocation having failed.
        audit.record(new AuditEvent(userId, "token_revoke_all", "user", userId,
                Map.of("authorizations", authorizations, "access_tokens", access,
                        "refresh_tokens", refresh, "codes", codes)));
    }
}
