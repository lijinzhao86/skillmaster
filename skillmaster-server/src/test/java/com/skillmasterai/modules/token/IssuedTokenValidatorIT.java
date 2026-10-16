package com.skillmasterai.modules.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.auth.TokenValidation;
import com.skillmasterai.modules.auth.TokenValidator;
import com.skillmasterai.support.AbstractAuthorizationCodeIT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * key. Since v3 the two kinds had separate vocabularies, and the split was pinned by two files
 * asserting apart from each other: this one took a single {@code unauthenticated} for everything
 * wrong with an OAuth token, while {@code ApiKeyIT} covered the side that named its reason.
 *
 * <p><strong>v4 unified them, and this is the file that changed</strong> — the paragraph that used to
 * stand here said the day it happened, one of the two would be edited, and this is that edit. Why it
 * had to: v4 is what gave an OAuth token a client ({@code skillmaster login} mints one), and a person
 * whose login died has to be told <em>which ruler ran out</em>, because the two lead somewhere
 * different (prd 验收 #5(a), #11). The order of the judgements is the key path's — a deliberate act
 * reported before the passage of time — and the fourth reason, {@link ErrorCode#TOKEN_IDLE}, is the
 * one only a login can produce.
 *
 * <p><strong>The slide is covered here too, and its cases are the ones that would be quietly wrong</strong>
 * (v4 §数据模型): a use inside the window that writes nothing, a use past it that writes, the ceiling
 * pinning that write, a use already at the ceiling that writes nothing, and a credential that died of
 * idleness never being slid back to life.
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
                .isEqualTo(ErrorCode.TOKEN_REVOKED);
    }

    /**
     * The parent's mark, which is the one the console makes when it ends a login.
     *
     * <p>New in v4, and it is why this path reads the parent as a value instead of testing it with
     * {@code EXISTS}: "unauthenticated" and "the login you are holding was ended" are different things
     * to be told, and only the second one tells a person what actually happened. The token row itself
     * is left alone here, which is the point — the console marks the authorization.
     */
    @Test
    void aTokenWhoseAuthorizationWasEndedIsNotAccepted() {
        String token = signInThroughTheCliFlow().accessToken();

        jdbc.sql("UPDATE oauth_authorization SET revoked_at = '2026-01-01T00:00:00Z'").update();

        assertThat(reasonFor(token)).as("an ended login's token was accepted")
                .isEqualTo(ErrorCode.TOKEN_REVOKED);
    }

    /**
     * The absolute ceiling — prd 验收 #5(b).
     *
     * <p><strong>Both dates move, and that is not sloppiness.</strong> The ceiling is not stored: it
     * is {@code created_at} plus the configured window, so "past the ceiling" is a property of the
     * issuance. Moving only {@code expires_at} would describe a state the sliding design cannot reach
     * (v4 §数据模型) and would answer {@link ErrorCode#TOKEN_IDLE} — see the case below.
     */
    @Test
    void aTokenPastItsAbsoluteCeilingIsNotAccepted() {
        // A login credential, not a framework one: the ceiling is *this version's* second ruler, and a
        // row without a label has only one date and therefore only one way to run out (see below).
        String token = aLoginCredential().accessToken();

        jdbc.sql("UPDATE access_token SET created_at = '2000-01-01T00:00:00Z',"
                + " expires_at = '2000-01-01T00:00:00Z'").update();

        assertThat(reasonFor(token)).as("a token past its ceiling was accepted")
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    /**
     * A third-party token's expiry is an expiry, and never idleness.
     *
     * <p>New with the fix that introduced it: the question "which of the two rulers ran out" was
     * being asked of every access token, including ones that have a single one-hour date and a refresh
     * chain behind them. Answered of those, it says {@code token_idle} — a ruler that shape does not
     * have, and a sentence that would send somebody looking for a window that was never there.
     */
    @Test
    void aThirdPartyTokensExpiryIsNotCalledIdleness() {
        String token = signInThroughTheCliFlow().accessToken();

        // Long enough ago that the ceiling test would have called it idle, had it been asked.
        jdbc.sql("UPDATE access_token SET expires_at = '2000-01-01T00:00:00Z'").update();

        assertThat(reasonFor(token)).as("a third-party expiry was reported as idleness")
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    /**
     * The idle ruler — prd 验收 #5(a), and the case that made a fourth reason necessary.
     *
     * <p>The expiry is in the past while the issuance is recent, which is exactly what "this machine
     * has not used it for longer than the ruler allows" looks like once the expiry slides. It has to
     * be told apart from the ceiling case above: both mean logging in again, but only an expired
     * credential is also worth answering with "or issue a key that lasts".
     */
    @Test
    void aTokenPastItsIdleRulerIsNotAccepted() {
        // Labelled, because the idle ruler belongs to the credentials this version issues — an
        // unlabelled row is refused as expired, and that case has its own test below.
        String token = aLoginCredential().accessToken();

        jdbc.sql("UPDATE access_token SET expires_at = '2000-01-01T00:00:00Z'").update();

        assertThat(reasonFor(token)).as("an idle token was accepted")
                .isEqualTo(ErrorCode.TOKEN_IDLE);
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
                    .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
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

    // ------------------------------------------------------------------
    // The slide: "idle 24 hours", expressed in one column
    // ------------------------------------------------------------------

    /**
     * A use inside the sampling window writes nothing.
     *
     * <p>This is what keeps the request path near read-only, and it is also the state a machine that
     * logs in and runs one command is in: what it was issued with is already the value a slide would
     * write, so nothing is written at all.
     */
    @Test
    void aUseInsideTheSamplingWindowLeavesTheExpiryAlone() {
        CliSignIn signIn = aLoginCredential();
        String fresh = Timestamps.format(
                Instant.now().plus(properties.sessions().idle()).minusSeconds(1));
        jdbc.sql("UPDATE access_token SET expires_at = :fresh").param("fresh", fresh).update();

        assertThat(validator.validate(signIn.accessToken(), ADDRESS))
                .as("the credential should have been accepted")
                .isInstanceOf(TokenValidation.Accepted.class);

        assertThat(expiryOf()).as("a use inside the window rewrote the expiry").isEqualTo(fresh);
    }

    /** Past the window it moves to {@code now + idle} — which is the whole of the idle ruler. */
    @Test
    void aUsePastTheSamplingWindowSlidesTheExpiryForward() {
        CliSignIn signIn = aLoginCredential();
        // **In the future, not in the past.** An expiry that has already passed is a credential that
        // died of idleness, and the case below this one covers it — the row has to be *alive and
        // stale*, which is what a credential sitting unused for longer than the sampling window looks
        // like. An hour ahead is stale under any window this policy accepts, and it is also what the
        // framework's own token settings grant.
        jdbc.sql("UPDATE access_token SET expires_at = :stale")
                .param("stale", Timestamps.format(Instant.now().plus(1, ChronoUnit.HOURS)))
                .update();

        Instant before = Instant.now();
        assertThat(validator.validate(signIn.accessToken(), ADDRESS))
                .as("the credential should have been accepted")
                .isInstanceOf(TokenValidation.Accepted.class);
        Instant after = Instant.now();

        // Bounded rather than exact: the stored value is truncated to whole seconds, which is the
        // property that makes lexicographic order chronological in the first place.
        assertThat(Timestamps.parse(expiryOf())).isBetween(
                before.plus(properties.sessions().idle()).minusSeconds(2),
                after.plus(properties.sessions().idle()).plusSeconds(2));
    }

    /**
     * And the ceiling pins that write — the case that makes "absolute 7 days" true with no second
     * column to hold it.
     *
     * <p>Six days old, so a slide that only knew about idleness would push the expiry a day past what
     * the ceiling allows.
     */
    @Test
    void theAbsoluteCeilingPinsTheSlide() {
        CliSignIn signIn = aLoginCredential();
        Instant created =
                Timestamps.parse(Timestamps.format(Instant.now().minus(6, ChronoUnit.DAYS)));
        jdbc.sql("UPDATE access_token SET created_at = :created, expires_at = :stale")
                .param("created", Timestamps.format(created))
                .param("stale", Timestamps.format(Instant.now().plus(1, ChronoUnit.HOURS)))
                .update();

        assertThat(validator.validate(signIn.accessToken(), ADDRESS))
                .as("the credential should have been accepted")
                .isInstanceOf(TokenValidation.Accepted.class);

        assertThat(expiryOf()).as("the slide went past the absolute ceiling")
                .isEqualTo(Timestamps.format(created.plus(properties.sessions().absolute())));
    }

    /**
     * A use that is already at the ceiling writes nothing, rather than rewriting one value for the
     * rest of the credential's life.
     *
     * <p>Six days and an hour old, expiry exactly at the ceiling: under a day away, so the sampling
     * condition holds, and the target equals what is already stored. Without the
     * {@code expires_at < :target} guard in the statement, every sampled request for the next six days
     * would write this same value.
     */
    @Test
    void aUseAtTheCeilingWritesNothing() {
        CliSignIn signIn = aLoginCredential();
        Instant created = Timestamps.parse(
                Timestamps.format(Instant.now().minus(6, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS)));
        String atCeiling = Timestamps.format(created.plus(properties.sessions().absolute()));
        jdbc.sql("UPDATE access_token SET created_at = :created, expires_at = :ceiling")
                .param("created", Timestamps.format(created))
                .param("ceiling", atCeiling)
                .update();

        assertThat(validator.validate(signIn.accessToken(), ADDRESS))
                .as("the credential should have been accepted")
                .isInstanceOf(TokenValidation.Accepted.class);

        assertThat(expiryOf()).isEqualTo(atCeiling);
    }

    /**
     * A credential that died of idleness is refused, not slid back to life.
     *
     * <p>The ordering invariant, and the one that would be invisible if it broke: the slide runs only
     * on the accepted path, so a date that has passed is answered rather than rewritten.
     */
    @Test
    void aCredentialThatDiedOfIdlenessIsNotSlidBackToLife() {
        CliSignIn signIn = aLoginCredential();
        String dead = Timestamps.format(Instant.now().minus(2, ChronoUnit.HOURS));
        jdbc.sql("UPDATE access_token SET expires_at = :dead").param("dead", dead).update();

        assertThat(reasonFor(signIn.accessToken())).isEqualTo(ErrorCode.TOKEN_IDLE);
        assertThat(expiryOf()).as("a refused credential was slid forward").isEqualTo(dead);
    }

    /**
     * A third-party credential does not slide, and this is the assertion that keeps v4 inside its
     * own boundary.
     *
     * <p>Same stale expiry as the sliding cases, but no label — which is what every authorization the
     * framework writes looks like. Its one-hour lifetime and its refresh chain are v4's neighbours, not
     * its business (Q4), and sliding it here would quietly make a stolen third-party token worth
     * twenty-four times more.
     */
    @Test
    void aThirdPartyCredentialDoesNotSlide() {
        CliSignIn signIn = signInThroughTheCliFlow();
        String stale = Timestamps.format(Instant.now().plus(1, ChronoUnit.HOURS));
        jdbc.sql("UPDATE access_token SET expires_at = :stale").param("stale", stale).update();

        assertThat(validator.validate(signIn.accessToken(), ADDRESS))
                .as("the credential should have been accepted")
                .isInstanceOf(TokenValidation.Accepted.class);

        assertThat(expiryOf()).as("a third-party credential was slid").isEqualTo(stale);
    }

    /**
     * A sign-in shaped like the one v4 issues.
     *
     * <p>The label is what marks it. This suite reaches the request path through the authorization
     * server's own flow, and an authorization written that way has no label — it is the third-party
     * shape, which the rulers deliberately do not apply to. Setting it is what makes these rows the
     * ones the slide is for, and it is the same condition the validator reads.
     */
    private CliSignIn aLoginCredential() {
        CliSignIn signIn = signInThroughTheCliFlow();
        jdbc.sql("UPDATE oauth_authorization SET label = 'test machine'").update();
        return signIn;
    }

    /** The one token row a sign-in writes, which every case above starts from. */
    private String expiryOf() {
        return jdbc.sql("SELECT expires_at FROM access_token").query(String.class).single();
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
