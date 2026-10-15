package com.skillmasterai.modules.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.auth.TokenValidation;
import com.skillmasterai.modules.auth.TokenValidator;
import com.skillmasterai.support.AbstractIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The request path's half of M2: a token that was issued is accepted, and one that is not is not.
 *
 * <p>These exist because swapping P0's configured token for an issued one is a change the rest of
 * the suite cannot see. Every API test now mints a token and uses it, so they would all keep passing
 * if this validator accepted anything at all — the assertion that matters is the one about a token
 * that <em>should</em> be refused, and nothing else in the suite makes it.
 *
 * <p><strong>The OAuth half of this class also pins a decision.</strong> OAuth tokens are left
 * refusing with a single {@code unauthenticated}, while API keys name their reason
 * ({@code ApiKeyIT} covers that side). The two are asserted apart on purpose: the day somebody
 * unifies them, one of these two files has to be edited, and the diff is where the conversation
 * happens.
 */
class IssuedTokenValidatorIT extends AbstractIT {

    /** No socket behind these calls, which is the state the validator has to survive. */
    private static final String ADDRESS = "203.0.113.7";

    @Autowired
    private TokenValidator validator;

    @Test
    void anIssuedTokenResolvesToItsSubjectAndItsScopes() {
        String token = token(Scopes.SKILLS_READ);

        TokenValidation result = validator.validate(token, ADDRESS);

        assertThat(result).as("an issued token was not accepted").isInstanceOf(
                TokenValidation.Accepted.class);
        AuthenticatedSubject subject = ((TokenValidation.Accepted) result).subject();
        // The subject is the userId the token acts as — which for an unattended client is the account
        // it was registered against, not the client itself (ADR 0022). A token whose user_id were the
        // client id would fail here, and would fail harder at `AuthenticatedSubject`, which refuses
        // anything that is not a ULID.
        assertThat(subject.userId()).isEqualTo(SUBJECT_USER_ID);
        assertThat(subject.scopes()).containsExactly(Scopes.SKILLS_READ);
        // An OAuth token is not a key, and says so rather than leaving the field to be guessed at.
        // `/api/v1/me` reads this to answer "which key am I holding" with null.
        assertThat(subject.apiKeyId()).isNull();
    }

    @Test
    void aRevokedTokenIsNotAccepted() {
        String token = token();
        assertThat(validator.validate(token, ADDRESS)).as("the token should work before revocation")
                .isInstanceOf(TokenValidation.Accepted.class);

        jdbc.sql("UPDATE access_token SET revoked_at = '2026-01-01T00:00:00Z'"
                + " WHERE revoked_at IS NULL").update();

        // The whole reason ADR 0021 chose opaque tokens over JWTs: revocation has to be visible on
        // this path, immediately. A self-contained token would still verify here.
        assertThat(reasonFor(token)).as("a revoked token was accepted")
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void anExpiredTokenIsNotAccepted() {
        String token = token();

        jdbc.sql("UPDATE access_token SET expires_at = '2000-01-01T00:00:00Z'").update();

        assertThat(reasonFor(token)).as("an expired token was accepted")
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void aTokenMintedForAnotherAudienceIsNotAccepted() {
        String token = token();

        // "This token is genuine" and "this token is genuine for here" are the same question only
        // while there is one resource — and §3.1 makes the check a requirement rather than a
        // consequence, so it is asserted rather than assumed.
        jdbc.sql("UPDATE access_token SET audience = 'https://somewhere-else.test'").update();

        assertThat(reasonFor(token)).as("a token minted for another audience was accepted")
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void aTokenWhoseAccountIsSuspendedIsNotAccepted() {
        String token = token();
        assertThat(validator.validate(token, ADDRESS)).as("the token should work while active")
                .isInstanceOf(TokenValidation.Accepted.class);

        // New in v2, and it is the same rule the key path applies: "who may act" is read on every
        // request rather than at the moment a credential was issued. This was a registered debt
        // before — see §架构 — and a 90-day credential is what made it a requirement.
        suspend(SUBJECT_USER_ID);
        try {
            assertThat(reasonFor(token)).as("a suspended account's token was accepted")
                    .isEqualTo(ErrorCode.UNAUTHENTICATED);
        } finally {
            // Restored rather than left suspended: this class shares one context, and a test that
            // poisoned the seeded account would fail the next one for a reason it does not name.
            restore(SUBJECT_USER_ID);
        }
    }

    @Test
    void anUnknownOrAbsentTokenIsNotAccepted() {
        // Every kind of "not ours" answers the same way, and that is the boundary TokenValidation
        // draws: a caller who presented no credential, or one that matches nothing, learns only that
        // it was not accepted. The named reasons are for credentials this service has a row for.
        assertThat(reasonFor("not-a-token-this-server-ever-issued"))
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(reasonFor("")).isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(reasonFor(null)).isEqualTo(ErrorCode.UNAUTHENTICATED);
        // And the same for a string wearing a key's prefix without being one: the route is taken, the
        // lookup misses, and a miss is not an acceptance (§接口).
        assertThat(reasonFor(ApiKeyFormat.PREFIX + "0".repeat(43)))
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    private ErrorCode reasonFor(String token) {
        TokenValidation result = validator.validate(token, ADDRESS);
        assertThat(result).as("expected a refusal for %s", token)
                .isInstanceOf(TokenValidation.Rejected.class);
        return ((TokenValidation.Rejected) result).reason();
    }

    private void suspend(String userId) {
        jdbc.sql("UPDATE app_user SET status = 'suspended' WHERE id = :id").param("id", userId)
                .update();
    }

    private void restore(String userId) {
        jdbc.sql("UPDATE app_user SET status = 'active' WHERE id = :id").param("id", userId).update();
    }
}
