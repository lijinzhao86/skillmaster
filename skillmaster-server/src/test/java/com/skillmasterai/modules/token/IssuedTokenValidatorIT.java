package com.skillmasterai.modules.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.auth.TokenValidation;
import com.skillmasterai.modules.auth.TokenValidator;
import com.skillmasterai.support.AbstractAuthorizationCodeIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

/**
 * The request path's half of M2: a token that was issued is accepted, and one that is not is not.
 *
 * <p>These exist because swapping P0's configured token for an issued one is a change the rest of
 * the suite cannot see. Every API test now presents a credential and uses it, so they would all keep
 * passing if this validator accepted anything at all — the assertion that matters is the one about a
 * token that <em>should</em> be refused, and nothing else in the suite makes it.
 *
 * <p><strong>Everything here is about an OAuth token, which is why this class drives the browser
 * flow</strong> ({@link AbstractAuthorizationCodeIT}) rather than being handed the suite's usual
 * key. Since v3 the two kinds are validated on different paths with different vocabularies, and the
 * decision to keep them apart is pinned by the two files asserting separately: this one takes a
 * single {@code unauthenticated} for everything wrong with an OAuth token, while {@code ApiKeyIT}
 * covers the side that names its reason. The day somebody unifies them, one of the two has to be
 * edited, and the diff is where the conversation happens.
 */
@Sql("/sql/truncate-business-tables.sql")
class IssuedTokenValidatorIT extends AbstractAuthorizationCodeIT {

    /** No socket behind these calls, which is the state the validator has to survive. */
    private static final String ADDRESS = "203.0.113.7";

    @Autowired
    private TokenValidator validator;

    @Test
    void anIssuedTokenResolvesToItsSubjectAndItsScopes() {
        CliSignIn signIn = signInThroughTheCliFlow();

        TokenValidation result = validator.validate(signIn.accessToken(), ADDRESS);

        assertThat(result).as("an issued token was not accepted").isInstanceOf(
                TokenValidation.Accepted.class);
        AuthenticatedSubject subject = ((TokenValidation.Accepted) result).subject();
        // The subject is the person the flow signed in, not the client that asked for the token: a
        // client is a program, and this API acts for people (ADR 0014). A token whose user id were
        // the client id would fail here, and would fail harder at `AuthenticatedSubject`, which
        // refuses anything that is not a ULID.
        assertThat(subject.userId()).isEqualTo(signIn.userId());
        // Both, because both were consented to — and because the authorize request asks for the pair
        // in one round trip (a second consent screen for the other half would be a second screen).
        assertThat(subject.scopes())
                .containsExactlyInAnyOrder(Scopes.SKILLS_READ, Scopes.SKILLS_WRITE);
        // An OAuth token is not a key, and says so rather than leaving the field to be guessed at.
        // `/api/v1/me` reads this to answer "which key am I holding" with null.
        assertThat(subject.apiKeyId()).isNull();
    }

    @Test
    void aRevokedTokenIsNotAccepted() {
        String token = signInThroughTheCliFlow().accessToken();
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
        String token = signInThroughTheCliFlow().accessToken();

        jdbc.sql("UPDATE access_token SET expires_at = '2000-01-01T00:00:00Z'").update();

        assertThat(reasonFor(token)).as("an expired token was accepted")
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void aTokenMintedForAnotherAudienceIsNotAccepted() {
        String token = signInThroughTheCliFlow().accessToken();

        // "This token is genuine" and "this token is genuine for here" are the same question only
        // while there is one resource — and §3.1 makes the check a requirement rather than a
        // consequence, so it is asserted rather than assumed.
        jdbc.sql("UPDATE access_token SET audience = 'https://somewhere-else.test'").update();

        assertThat(reasonFor(token)).as("a token minted for another audience was accepted")
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void aTokenWhoseAccountIsSuspendedIsNotAccepted() {
        CliSignIn signIn = signInThroughTheCliFlow();
        assertThat(validator.validate(signIn.accessToken(), ADDRESS))
                .as("the token should work while active")
                .isInstanceOf(TokenValidation.Accepted.class);

        // New in v2, and it is the same rule the key path applies: "who may act" is read on every
        // request rather than at the moment a credential was issued. This was a registered debt
        // before — see §架构 — and a 90-day credential is what made it a requirement.
        suspend(signIn.userId());
        try {
            assertThat(reasonFor(signIn.accessToken())).as("a suspended account's token was accepted")
                    .isEqualTo(ErrorCode.UNAUTHENTICATED);
        } finally {
            // Restored rather than left suspended: this class mints a fresh account per test, but the
            // rows are not truncated away from `app_user`, and a test that poisoned an account would
            // fail the next one for a reason it does not name.
            restore(signIn.userId());
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
