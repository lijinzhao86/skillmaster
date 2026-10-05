package com.skillmasterai.modules.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.auth.TokenValidator;
import com.skillmasterai.support.AbstractIT;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The request path's half of M2: a token that was issued is accepted, and one that is not is not.
 *
 * <p>These exist because swapping P0's configured token for an issued one is a change the rest of
 * the suite cannot see. Every API test now mints a token and uses it, so they would all keep passing
 * if this validator accepted anything at all — the assertion that matters is the one about a token
 * that <em>should</em> be refused, and nothing else in the suite makes it.
 */
class IssuedTokenValidatorIT extends AbstractIT {

    @Autowired
    private TokenValidator validator;

    @Test
    void anIssuedTokenResolvesToItsSubjectAndItsScopes() {
        String token = token(Scopes.SKILLS_READ);

        Optional<AuthenticatedSubject> subject = validator.validate(token);

        assertThat(subject).as("an issued token was not accepted").isPresent();
        // The subject is the userId the token acts as — which for an unattended client is the account
        // it was registered against, not the client itself (ADR 0022). A token whose user_id were the
        // client id would fail here, and would fail harder at `AuthenticatedSubject`, which refuses
        // anything that is not a ULID.
        assertThat(subject.orElseThrow().userId()).isEqualTo(SUBJECT_USER_ID);
        assertThat(subject.orElseThrow().scopes()).containsExactly(Scopes.SKILLS_READ);
    }

    @Test
    void aRevokedTokenIsNotAccepted() {
        String token = token();
        assertThat(validator.validate(token)).as("the token should work before revocation").isPresent();

        jdbc.sql("UPDATE access_token SET revoked_at = '2026-01-01T00:00:00Z'"
                + " WHERE revoked_at IS NULL").update();

        // The whole reason ADR 0021 chose opaque tokens over JWTs: revocation has to be visible on
        // this path, immediately. A self-contained token would still verify here.
        assertThat(validator.validate(token))
                .as("a revoked token was accepted")
                .isEmpty();
    }

    @Test
    void anExpiredTokenIsNotAccepted() {
        String token = token();

        jdbc.sql("UPDATE access_token SET expires_at = '2000-01-01T00:00:00Z'").update();

        assertThat(validator.validate(token)).as("an expired token was accepted").isEmpty();
    }

    @Test
    void aTokenMintedForAnotherAudienceIsNotAccepted() {
        String token = token();

        // "This token is genuine" and "this token is genuine for here" are the same question only
        // while there is one resource — and §3.1 makes the check a requirement rather than a
        // consequence, so it is asserted rather than assumed.
        jdbc.sql("UPDATE access_token SET audience = 'https://somewhere-else.test'").update();

        assertThat(validator.validate(token))
                .as("a token minted for another audience was accepted")
                .isEmpty();
    }

    @Test
    void anUnknownOrAbsentTokenIsNotAccepted() {
        // Every kind of no answers the same way: the caller cannot tell "no such token" from
        // "expired" or "revoked", which is what the interface requires.
        assertThat(validator.validate("not-a-token-this-server-ever-issued")).isEmpty();
        assertThat(validator.validate("")).isEmpty();
        assertThat(validator.validate(null)).isEmpty();
    }
}
