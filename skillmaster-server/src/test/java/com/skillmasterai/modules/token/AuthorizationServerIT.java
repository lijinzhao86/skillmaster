package com.skillmasterai.modules.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.support.AbstractAccountIT;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * M2's whole lifecycle, driven through the real endpoints against a real database.
 *
 * <p>Six claims, and each of them is one the design would be wrong about if it failed:
 *
 * <ol>
 *   <li><strong>The browser session is enough to authorize.</strong> The test signs in through M1's
 *       own endpoint and never authenticates again; {@code /oauth/authorize} finds the user in the
 *       session, which is the whole of the M1→M2 boundary (ADR 0014).
 *   <li><strong>Nothing but sha256 is stored.</strong> Asserted over the rows themselves, because the
 *       point of ADR 0007's "a database leak is not a token leak" is a claim about what is on disk —
 *       a response body cannot show it.
 *   <li><strong>A public client gets a refresh token, and rotating it works.</strong> The framework
 *       declines both by default; those are the two mechanisms ADR 0026 supplies, and if either
 *       silently stopped working, the CLI would appear to log in and then fail a day later.
 *   <li><strong>Revoking ends the authorization, not just the token presented.</strong> The access
 *       token issued minutes earlier must stop being live at the same moment, which is why
 *       {@code authorization_id} exists.
 *   <li><strong>A refresh token has two lifetimes and one grace window</strong> (ADR 0024), and the
 *       ceiling half is the one the framework does not have a setting for — it is enforced against
 *       {@code oauth_authorization.created_at}, so a rotated token cannot outlive the sign-in it
 *       descends from. The framework's provider also does nothing about reuse beyond
 *       {@code invalid_grant}, so the chain revocation past the window is ours to assert.
 *   <li><strong>A password reset ends the tokens, not only the sessions.</strong> The reset is the
 *       entry point {@code revokeAllFor} was written for (ADR 0013), and it is asserted over HTTP
 *       rather than by calling the module — whether anything can <em>reach</em> the method is the part
 *       that was missing.
 * </ol>
 *
 * <p>Deliberately not here: the consent page itself. The framework's redirect to it is asserted, and
 * the submission it expects is posted directly — the page is the SPA's, and testing it would test the
 * page rather than the protocol underneath it.
 */
@Sql("/sql/truncate-business-tables.sql")
class AuthorizationServerIT extends AbstractAccountIT {

    private static final String CLIENT_ID = "skillmaster-cli";

    /**
     * A fixed loopback port, and a registered redirect URI that matches it character for character.
     *
     * <p>The design wants an ephemeral port, and the framework will not allow one: it compares the
     * requested redirect URI against the registered set with {@code contains} and knows nothing about
     * RFC 8252 §8.4's exception for the port. That is an open decision, recorded in the module doc;
     * until it is taken, this is the shape that works, and it is also what a deployment would have to
     * seed.
     */
    private static final String REDIRECT_URI = "http://127.0.0.1:51004/callback";

    private static final String AUTHORIZE = "/oauth/authorize";
    private static final String TOKEN = "/oauth/token";
    private static final String REVOKE = "/oauth/revoke";
    private static final String CONSENT_PAGE = "/consent";

    /** What the reset in {@link #resettingThePasswordEndsTheCredentialsAMachineWasStillHolding} sets. */
    private static final String RESET_PASSWORD = "a-different-long-password";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private TokenRevocation tokenRevocation;

    /**
     * The store, through the interface the framework uses. Needed by the race test, which has to put
     * an authorization into the state an in-flight refresh would be holding when a revocation lands.
     */
    @Autowired
    private OAuth2AuthorizationService authorizations;

    /**
     * The CLI's row, seeded by hand — v1 has no registration endpoint and this is what a deployment
     * does instead (ADR 0011, the M02 module doc). {@code ON CONFLICT} because {@code oauth_client} is
     * not truncated between tests: it is a deployment fact, not a fixture.
     */
    private void seedCliClient() {
        jdbc.sql("INSERT INTO oauth_client (client_id, name, registration, redirect_uris, grant_types,"
                        + " client_secret_hash, metadata, created_at)"
                        + " VALUES (:id, 'SkillMaster CLI', 'preregistered', :redirectUris, :grantTypes,"
                        + " NULL, :metadata, '2026-01-01T00:00:00Z')"
                        + " ON CONFLICT (client_id) DO UPDATE SET redirect_uris = EXCLUDED.redirect_uris,"
                        + " metadata = EXCLUDED.metadata")
                .param("id", CLIENT_ID)
                .param("redirectUris", "[\"" + REDIRECT_URI + "\"]")
                .param("grantTypes", "[\"authorization_code\",\"refresh_token\"]")
                .param("metadata", "{\"scopes\":[\"skills:read\",\"skills:write\"]}")
                .update();
    }

    /**
     * The discovery document has to be readable by a machine that has never authenticated — that is
     * its entire purpose (RFC 9728, RFC 8414). The paths below are the framework's, and they are what
     * a client reads to learn that this server says {@code /oauth/token} rather than {@code
     * /oauth2/token} (see TokenSecurityConfig).
     */
    @Test
    void theDiscoveryDocumentIsReadableWithoutCredentials() {
        HttpResponse<String> metadata = send(HttpRequest.newBuilder(
                uri("/.well-known/oauth-authorization-server")).GET().build());
        assertThat(metadata.statusCode()).as("discovery: %s", metadata.body()).isEqualTo(200);
        assertThat(body(metadata).get("token_endpoint").asText()).endsWith("/oauth/token");
        assertThat(body(metadata).get("authorization_endpoint").asText()).endsWith("/oauth/authorize");
        assertThat(body(metadata).get("revocation_endpoint").asText()).endsWith("/oauth/revoke");
    }

    /**
     * The half of the flow that happens before anyone is signed in.
     *
     * <p>What matters is the parameter: {@code return_to} is what {@code LoginPage.vue} reads to send
     * the visitor back, and without it the CLI waits on its loopback port for a callback that never
     * comes — a login that looked like it worked and a command that hangs. Anonymous on purpose:
     * {@code send} carries no cookie jar, so this is the browser nobody has signed in on.
     */
    @Test
    void anUnauthenticatedAuthorizationRequestSaysWhereToComeBackTo() {
        seedCliClient();

        HttpResponse<String> toLogin = send(HttpRequest.newBuilder(
                uri(AUTHORIZE + "?" + form(authorizationQuery(codeVerifier(), "state-nowhere"))))
                .GET().build());

        assertThat(toLogin.statusCode()).as("an anonymous authorization request: %s", toLogin.body())
                .isEqualTo(302);
        String redirect = location(toLogin);
        assertThat(URI.create(redirect).getPath()).isEqualTo("/login");
        assertThat(queryParameter(redirect, "return_to"))
                .as("where the login page has to send the browser back to: %s", redirect)
                .startsWith(AUTHORIZE + "?")
                .contains("client_id=" + CLIENT_ID);
    }

    /**
     * A refusal from this plane reads as a refusal, not as the API plane's 401.
     *
     * <p><strong>This is the error dispatch, and it is easy to lose without noticing.</strong> When a
     * request fails in a chain that sits *above* the catch-all, the container dispatches to
     * {@code /error}, and that dispatch re-matches the chains from the top — so it landed on the
     * catch-all, whose entry point answered for an anonymous caller. The framework's own explanation
     * was replaced by {@code 401 unauthenticated} with a Bearer challenge, naming a plane the caller
     * is not on and hiding what was wrong with the request. Observed 2026-10-05 against a bad consent
     * submission, and it is why {@code /error} is permitted in the catch-all.
     *
     * <p>An unknown {@code client_id} is the case that cannot be answered any other way: the
     * framework has nowhere to redirect an error to, so it has to write one.
     */
    @Test
    void aBadAuthorizationRequestIsRefusedByThisPlaneAndNotByTheApiPlane() {
        seedCliClient();
        registerAndSignIn(randomUsername(), randomPhone());

        HttpResponse<String> refused = browser.get(uri(AUTHORIZE
                + "?response_type=code&client_id=not-a-registered-client"
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)));

        assertThat(refused.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE))
                .as("the API plane's challenge leaked into this one: %s", refused.body())
                .isEmpty();
        assertThat(refused.statusCode())
                .as("a malformed authorization request: %s", refused.body())
                .isEqualTo(400);
    }

    /**
     * Refusing, which is the other half of what consent means.
     *
     * <p>A declined authorization ends at the client's redirect URI with {@code access_denied} and no
     * code — so the CLI learns the person said no rather than waiting for something that is not
     * coming. Nothing is written: no code, and therefore no authorization to revoke later.
     */
    @Test
    void refusingConsentSendsTheClientAnErrorAndIssuesNoCode() {
        seedCliClient();
        registerAndSignIn(randomUsername(), randomPhone());

        String verifier = codeVerifier();
        HttpResponse<String> toConsent = browser.get(uri(AUTHORIZE + "?"
                + form(authorizationQuery(verifier, "state-" + randomUsername()))));
        String consentState = queryParameter(location(toConsent), "state");

        // Declining is the same submission with nothing agreed to — the page's other button.
        HttpResponse<String> denied = browser.postForm(uri(AUTHORIZE),
                form(Map.of("client_id", List.of(CLIENT_ID), "state", List.of(consentState))),
                browser.csrfToken());

        assertThat(denied.statusCode()).as("a declined authorization: %s", denied.body()).isEqualTo(302);
        assertThat(queryParameter(location(denied), "error")).isEqualTo("access_denied");
        assertThat(queryParameter(location(denied), "code")).isNull();
        assertThat(count("SELECT count(*) FROM auth_code")).isZero();
    }

    @Test
    void aPublicClientRunsConsentToRevocationAndStoresNothingButHashes() {
        seedCliClient();
        registerAndSignIn(randomUsername(), randomPhone());
        String userId = body(webGet(SESSION)).get("user_id").asText();

        String verifier = codeVerifier();
        String state = "state-" + randomUsername();
        String code = consentAndGetCode(verifier, state);

        assertThat(count("SELECT count(*) FROM auth_code WHERE used_at IS NULL")).isEqualTo(1);

        // 3. The code is exchanged by the client directly, never through the browser. A public client
        //    proves itself with the verifier, and a refresh token comes back — which the framework
        //    would have refused to mint (see RefreshTokenForPublicClients).
        JsonNode issued = body(token(form(Map.of(
                "grant_type", List.of("authorization_code"),
                "code", List.of(code),
                "redirect_uri", List.of(REDIRECT_URI),
                "client_id", List.of(CLIENT_ID),
                "code_verifier", List.of(verifier)))));
        String accessToken = issued.get("access_token").asText();
        String refreshToken = issued.get("refresh_token").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).as("a public client's refresh token: %s", issued).isNotBlank();

        // 4. What is on disk. Not "the response did not contain them" — the claim is about the rows.
        assertThat(storedTokenValues())
                .as("every stored token value must be a bare sha256")
                .isNotEmpty()
                .allMatch(value -> value.matches("[0-9a-f]{64}"))
                .doesNotContain(accessToken, refreshToken, code);
        assertThat(count("SELECT count(*) FROM oauth_authorization")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM access_token")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM auth_code WHERE used_at IS NOT NULL"))
                .as("a redeemed code is spent, not deleted")
                .isEqualTo(1);

        // The subject is the userId and not the username: the same value M3's AuthenticatedSubject
        // will carry, and the one thing here that could be wrong without any test in M1 noticing.
        assertThat(jdbc.sql("SELECT user_id FROM access_token").query(String.class).single())
                .isEqualTo(userId);

        // 5. Rotation. The token that was spent is revoked and the new one points back at it — the
        //    link ADR 0024's replay recounting walks.
        JsonNode rotated = body(token(form(Map.of(
                "grant_type", List.of("refresh_token"),
                "refresh_token", List.of(refreshToken),
                "client_id", List.of(CLIENT_ID)))));
        String rotatedRefresh = rotated.get("refresh_token").asText();
        assertThat(rotatedRefresh).isNotEqualTo(refreshToken);
        assertThat(count("SELECT count(*) FROM refresh_token WHERE rotated_from IS NOT NULL"))
                .as("the new refresh token records the one it replaced")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM refresh_token WHERE revoked_at IS NOT NULL"))
                .as("the replaced token is spent")
                .isEqualTo(1);
        // Two access tokens now, both under one authorization — the older one still live, which is
        // what makes the next step a claim worth testing.
        assertThat(count("SELECT count(*) FROM access_token WHERE revoked_at IS NULL")).isEqualTo(2);

        // 6. Revocation ends the authorization, not the one token presented.
        HttpResponse<String> revoked = send(formRequest(REVOKE,
                form(Map.of("token", List.of(rotatedRefresh), "client_id", List.of(CLIENT_ID)))));
        assertThat(revoked.statusCode()).as("revoking: %s", revoked.body()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL"))
                .as("no refresh token of this authorization survives")
                .isZero();
        assertThat(count("SELECT count(*) FROM access_token WHERE revoked_at IS NULL"))
                .as("the live access token is cancelled too, rather than left to run out its hour")
                .isZero();

        // 7. And the revocation is visible where it has to be: the next refresh is refused.
        //
        //    Worth stating, because the grace window sits one step away from making this pass when it
        //    should not: this token was revoked seconds ago, so a window that looked only at the clock
        //    would forgive the retry and hand back a live pair — a logout that lasts sixty seconds.

        HttpResponse<String> refused = token(form(Map.of(
                "grant_type", List.of("refresh_token"),
                "refresh_token", List.of(rotatedRefresh),
                "client_id", List.of(CLIENT_ID))));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(body(refused).get("error").asText()).isEqualTo("invalid_grant");
    }

    /**
     * A refresh token presented twice, quickly: a race, not a theft.
     *
     * <p>This is the first half of ADR 0024, and it is the half that cannot be reasoned about without
     * driving it — the CLI is one process per command, so two commands starting together see the same
     * expired access token and both refresh with the same refresh token. The loser presents a token
     * that was rotated away microseconds earlier. The window exists so that loser gets a working pair
     * instead of a logout.
     */
    @Test
    void aRefreshTokenPresentedAgainInsideTheGraceWindowIsForgiven() {
        String spent = refreshTokenOfANewSignIn();
        String successor = refresh(spent);

        // The same token the rotation just spent, presented again — what the process that lost the
        // race holds. No clock is touched: this is inside the window by construction, which is the
        // point of the default being measured in seconds.
        HttpResponse<String> forgiven = token(form(Map.of(
                "grant_type", List.of("refresh_token"),
                "refresh_token", List.of(spent),
                "client_id", List.of(CLIENT_ID))));

        assertThat(forgiven.statusCode())
                .as("a refresh replayed inside the grace window: %s", forgiven.body())
                .isEqualTo(200);
        String forked = body(forgiven).get("refresh_token").asText();
        assertThat(forked).as("a forgiven race still rotates").isNotEqualTo(spent).isNotEqualTo(successor);
        // Nothing came down: the chain forks here rather than ending (ADR 0024's 后果).
        assertThat(count("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL"))
                .as("the forgiven race leaves one live token, not none")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM access_token WHERE revoked_at IS NULL"))
                .as("and the access tokens issued along the way stay live")
                .isEqualTo(3);
    }

    /**
     * The same token, long after the rotation: a replay.
     *
     * <p>The window is backdated rather than waited out — 60 seconds of slept test is 60 seconds of
     * nothing, and the window is a configuration value, not a behaviour. What is being pinned is that
     * the second half of ADR 0024 actually happens: the framework refuses the token and then does
     * nothing else about it, so the revocation of the whole chain has to be ours.
     */
    @Test
    void aRefreshTokenPresentedOutsideTheGraceWindowEndsTheChain() {
        String spent = refreshTokenOfANewSignIn();
        refresh(spent);
        assertThat(count("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL")).isEqualTo(1);

        // Past the 60-second window: a retry this late is not a race.
        jdbc.sql("UPDATE refresh_token SET revoked_at = :past WHERE revoked_at IS NOT NULL")
                .param("past", Timestamps.format(Instant.now().minus(Duration.ofMinutes(5))))
                .update();

        HttpResponse<String> replayed = token(form(Map.of(
                "grant_type", List.of("refresh_token"),
                "refresh_token", List.of(spent),
                "client_id", List.of(CLIENT_ID))));

        assertThat(replayed.statusCode()).isEqualTo(400);
        assertThat(body(replayed).get("error").asText()).isEqualTo("invalid_grant");
        // And the whole chain went with it — including the live token the rotation produced, which is
        // the one a thief would be holding. The person re-authorizes; nobody keeps working.
        assertThat(count("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL"))
                .as("a replay past the window leaves no live refresh token")
                .isZero();
        assertThat(count("SELECT count(*) FROM access_token WHERE revoked_at IS NULL"))
                .as("and no live access token either, rather than one left to run out its hour")
                .isZero();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'token_revoke'"
                        + " AND detail LIKE '%refresh_token_replayed%'"))
                .as("the trail says why this authorization ended, not that somebody logged out")
                .isEqualTo(1);
    }

    /**
     * Past the authorization's own ceiling, a perfectly good refresh token stops working.
     *
     * <p>ADR 0024's other ruler: the idle lifetime is about a credential nobody uses, and this is about
     * one that is used constantly and still has to end. It is anchored on the authorization rather than
     * on the token — a rotated token must not be able to outlive the sign-in it descends from.
     */
    @Test
    void aRefreshTokenStopsWorkingOnceTheAuthorizationIsPastItsCeiling() {
        String live = refreshTokenOfANewSignIn();

        // Past the 180-day ceiling without waiting 180 days. Anchored on the authorization, so this is
        // the only row that has to move.
        jdbc.sql("UPDATE oauth_authorization SET created_at = :past")
                .param("past", Timestamps.format(Instant.now().minus(Duration.ofDays(200))))
                .update();

        HttpResponse<String> refused = token(form(Map.of(
                "grant_type", List.of("refresh_token"),
                "refresh_token", List.of(live),
                "client_id", List.of(CLIENT_ID))));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(body(refused).get("error").asText()).isEqualTo("invalid_grant");
        // The ceiling refuses; it does not cascade. Nothing here was stolen, so there is nothing to
        // tear down, and the access token the client is holding expires on its own within the hour.
        assertThat(count("SELECT count(*) FROM access_token WHERE revoked_at IS NULL"))
                .as("a refusal at the ceiling is not a revocation")
                .isEqualTo(1);
    }

    /**
     * The other exit: everything a person holds, in one act.
     *
     * <p>This is the half of a password reset that M1 cannot do for itself, and it is called through
     * the interface the use-case layer will use — so the test fails if the seam stops being wired, not
     * just if the SQL stops working.
     */
    @Test
    void revokingEveryTokenForAUserEndsAllOfThem() {
        seedCliClient();
        registerAndSignIn(randomUsername(), randomPhone());
        String userId = body(webGet(SESSION)).get("user_id").asText();

        String verifier = codeVerifier();
        String code = consentAndGetCode(verifier, "state-" + randomUsername());
        body(token(form(Map.of(
                "grant_type", List.of("authorization_code"),
                "code", List.of(code),
                "redirect_uri", List.of(REDIRECT_URI),
                "client_id", List.of(CLIENT_ID),
                "code_verifier", List.of(verifier)))));
        assertThat(count("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL")).isEqualTo(1);

        tokenRevocation.revokeAllFor(userId);

        assertThat(count("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM access_token WHERE revoked_at IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM auth_code WHERE used_at IS NULL")).isZero();
    }

    /**
     * The same revocation, reached the way a person reaches it: a password reset over HTTP.
     *
     * <p>The test above calls the module's own method, so it pins the SQL and the module boundary and
     * says nothing about whether anything can get there — and for a while nothing could. The design
     * puts the call in the reset use case (ADR 0013: a password change that leaves the credentials
     * alive is not a password change), where M1 ends the sessions and M2 ends the tokens.
     *
     * <p>Both halves are asserted, because they fail differently. An access token that still works is
     * up to an hour of access that the person believes they just took away; a refresh token that still
     * works is a machine that renews and keeps going, for as long as the chain's ceiling allows.
     */
    @Test
    void resettingThePasswordEndsTheCredentialsAMachineWasStillHolding() {
        seedCliClient();
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);

        String verifier = codeVerifier();
        String code = consentAndGetCode(verifier, "state-" + randomUsername());
        JsonNode issued = body(token(form(Map.of(
                "grant_type", List.of("authorization_code"),
                "code", List.of(code),
                "redirect_uri", List.of(REDIRECT_URI),
                "client_id", List.of(CLIENT_ID),
                "code_verifier", List.of(verifier)))));
        String accessToken = issued.get("access_token").asText();
        String refreshToken = issued.get("refresh_token").asText();

        clearSmsCooldown();
        HttpResponse<String> reset = webPost(RESET, json(Map.of("phone", phone,
                "code", requestCode(RESET_CODE, phone), "password", RESET_PASSWORD)));
        assertThat(reset.statusCode()).as(reset.body()).isEqualTo(204);

        // The access token already in a machine's file: "the revocation takes effect now" is the claim
        // ADR 0021 was chosen for, and an hour of it still working is exactly what it rules out.
        assertThat(get("/api/v1/skills", accessToken).statusCode())
                .as("the access token that was live until the reset").isEqualTo(401);

        // The half that would otherwise come back on its own: the machine's next command renews, and
        // only the ended chain stops it.
        HttpResponse<String> renewed = token(form(Map.of(
                "grant_type", List.of("refresh_token"),
                "refresh_token", List.of(refreshToken),
                "client_id", List.of(CLIENT_ID))));
        assertThat(renewed.statusCode()).as("a refresh after a reset: %s", renewed.body())
                .isEqualTo(400);
        assertThat(body(renewed).get("error").asText()).isEqualTo("invalid_grant");
    }

    // ------------------------------------------------------------------
    // The OAuth plane, as the CLI sees it
    // ------------------------------------------------------------------

    /**
     * A signed-in person with a refresh token in hand, through the whole browser flow.
     *
     * <p>The three tests about ADR 0024 all start here and then do something to the clock, so the
     * setup is worth naming once. Returns the refresh token; the access token is not needed by any of
     * them.
     */
    private String refreshTokenOfANewSignIn() {
        seedCliClient();
        registerAndSignIn(randomUsername(), randomPhone());
        String verifier = codeVerifier();
        String code = consentAndGetCode(verifier, "state-" + randomUsername());
        JsonNode issued = body(token(form(Map.of(
                "grant_type", List.of("authorization_code"),
                "code", List.of(code),
                "redirect_uri", List.of(REDIRECT_URI),
                "client_id", List.of(CLIENT_ID),
                "code_verifier", List.of(verifier)))));
        String refreshToken = issued.get("refresh_token").asText();
        assertThat(refreshToken).as("a public client's refresh token: %s", issued).isNotBlank();
        return refreshToken;
    }

    /**
     * A refresh that lands after the authorization was ended does not bring it back.
     *
     * <p><strong>This is the race the token rows alone could not close.</strong> {@code save} reads
     * which refresh token is live to tell a rotation from an ending, and a revocation that lands
     * between that read and this write leaves **nothing** live — which is also exactly what the first
     * insert of a brand-new authorization looks like. So the freshly minted token was written live,
     * under an authorization that had just been ended, with the rest of its ceiling still to run. The
     * grace window could not catch it either: that window only governs a token being *presented*
     * again, and this is the write.
     *
     * <p>V9 gives the authorization its own {@code revoked_at} and the load path refuses everything
     * under it, so a row that escaped the sweep is inert. Driven at the store rather than through
     * HTTP, because the window is between two statements inside one request — a test that waited for
     * it would be a test that sometimes did not run.
     */
    @Test
    void aRefreshThatLandsAfterARevocationDoesNotBringTheAuthorizationBack() {
        seedCliClient();
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        String userId = body(webGet(SESSION)).get("user_id").asText();

        String verifier = codeVerifier();
        String code = consentAndGetCode(verifier, "state-" + randomUsername());
        JsonNode issued = body(token(form(Map.of(
                "grant_type", List.of("authorization_code"),
                "code", List.of(code),
                "redirect_uri", List.of(REDIRECT_URI),
                "client_id", List.of(CLIENT_ID),
                "code_verifier", List.of(verifier)))));
        String held = issued.get("refresh_token").asText();

        // The in-flight refresh has already loaded the authorization and minted its successor. The
        // revocation lands now, before that save.
        OAuth2Authorization inFlight = authorizations.findByToken(held, OAuth2TokenType.REFRESH_TOKEN);
        assertThat(inFlight).as("the authorization the refresh is working from").isNotNull();
        Instant now = Instant.now();
        String successor = "a-successor-nobody-will-be-able-to-use";
        OAuth2Authorization withSuccessor = OAuth2Authorization.from(inFlight)
                .token(new OAuth2RefreshToken(successor, now, now.plus(Duration.ofDays(30))))
                .build();

        tokenRevocation.revokeAllFor(userId);
        authorizations.save(withSuccessor);

        assertThat(count("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL"))
                .as("the token the racing refresh wrote must not be live")
                .isZero();

        // And the point of it: the token that process now holds cannot be used. Marked on load from
        // the authorization's own row, so it does not matter that the row escaped the sweep.
        assertThat(authorizations.findByToken(successor, OAuth2TokenType.REFRESH_TOKEN).getRefreshToken()
                .isActive()).isFalse();
        HttpResponse<String> refused = token(form(Map.of(
                "grant_type", List.of("refresh_token"),
                "refresh_token", List.of(successor),
                "client_id", List.of(CLIENT_ID))));
        assertThat(refused.statusCode()).as("a refresh with it: %s", refused.body()).isEqualTo(400);
        assertThat(body(refused).get("error").asText()).isEqualTo("invalid_grant");
    }

    /**
     * And a row that escaped the sweep anyway is still inert — that is what the flag is for.
     *
     * <p>A sweep is a set of {@code UPDATE}s, and no set of {@code UPDATE}s can be atomic with an
     * {@code INSERT} it has not seen yet. So the guarantee has to live somewhere a racing write cannot
     * get past: on the authorization's own row, which both the load path and the API's own token check
     * read. The rows below are written by hand precisely because the store will not produce this state
     * any more — which is the point of the two guards being separate.
     */
    @Test
    void aLiveRowThatEscapedTheRevocationIsStillInert() {
        seedCliClient();
        registerAndSignIn(randomUsername(), randomPhone());
        String userId = body(webGet(SESSION)).get("user_id").asText();

        String verifier = codeVerifier();
        String code = consentAndGetCode(verifier, "state-" + randomUsername());
        body(token(form(Map.of(
                "grant_type", List.of("authorization_code"),
                "code", List.of(code),
                "redirect_uri", List.of(REDIRECT_URI),
                "client_id", List.of(CLIENT_ID),
                "code_verifier", List.of(verifier)))));

        String authorizationId = jdbc.sql("SELECT id FROM oauth_authorization WHERE principal_name = :id")
                .param("id", userId).query(String.class).single();
        tokenRevocation.revokeAllFor(userId);

        // The racing writer's two rows, live again and shaped exactly like the ones beside them.
        String escapedRefresh = "a-refresh-row-the-sweep-never-saw";
        String escapedAccess = "an-access-row-the-sweep-never-saw";
        jdbc.sql("INSERT INTO refresh_token (token_hash, client_id, user_id, scope, expires_at,"
                        + " revoked_at, rotated_from, created_at, authorization_id)"
                        + " SELECT :hash, client_id, user_id, scope, expires_at, NULL, NULL, created_at,"
                        + " authorization_id FROM refresh_token WHERE authorization_id = :id LIMIT 1")
                .param("hash", hashOf(escapedRefresh)).param("id", authorizationId).update();
        jdbc.sql("INSERT INTO access_token (token_hash, client_id, user_id, scope, audience,"
                        + " expires_at, revoked_at, created_at, authorization_id)"
                        + " SELECT :hash, client_id, user_id, scope, audience, expires_at, NULL,"
                        + " created_at, authorization_id FROM access_token"
                        + " WHERE authorization_id = :id LIMIT 1")
                .param("hash", hashOf(escapedAccess)).param("id", authorizationId).update();
        assertThat(count("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL"))
                .as("the escaped rows are live, which is the state under test").isEqualTo(1);

        // The refresh path: marked invalid on load, from the authorization's own row.
        assertThat(authorizations.findByToken(escapedRefresh, OAuth2TokenType.REFRESH_TOKEN)
                .getRefreshToken().isActive()).isFalse();
        HttpResponse<String> refused = token(form(Map.of(
                "grant_type", List.of("refresh_token"),
                "refresh_token", List.of(escapedRefresh),
                "client_id", List.of(CLIENT_ID))));
        assertThat(refused.statusCode()).as("a refresh with it: %s", refused.body()).isEqualTo(400);
        assertThat(body(refused).get("error").asText()).isEqualTo("invalid_grant");

        // And the API path, which does not go through this store at all: its own query has to ask.
        assertThat(get("/api/v1/skills", escapedAccess).statusCode())
                .as("a live row under an ended authorization must not authenticate").isEqualTo(401);
    }

    private static String hashOf(String token) {
        return Sha256Hex.of(token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * `revokeAllFor` ends the unattended grant's authorization too, not only its tokens.
     *
     * <p><strong>The two grants spell the user differently, and the sweep knew only one spelling.</strong>
     * For `client_credentials` the framework's principal is the client authentication, so the
     * authorization row's `principal_name` is the CLIENT id — while its token rows carry the account
     * the client acts as (ADR 0022, and {@code subjectOf} exists to say so). A sweep keyed on
     * `principal_name` marked every token and left the parent row live, which is exactly the flag V9
     * made the authority: the audit detail read {@code authorizations: 0} beside
     * {@code access_tokens: 1}, and a count that is false is worse than one that is missing.
     */
    @Test
    void revokingEverythingForAUserAlsoEndsTheAuthorizationsOfClientsBoundToThem() {
        token();
        assertThat(count("SELECT count(*) FROM access_token WHERE user_id = :id AND revoked_at IS NULL",
                Map.of("id", SUBJECT_USER_ID))).isEqualTo(1);

        tokenRevocation.revokeAllFor(SUBJECT_USER_ID);

        assertThat(count("SELECT count(*) FROM access_token WHERE user_id = :id AND revoked_at IS NULL",
                Map.of("id", SUBJECT_USER_ID)))
                .as("the unattended client's token").isZero();
        assertThat(count("SELECT count(*) FROM oauth_authorization WHERE registered_client_id = :client"
                        + " AND revoked_at IS NULL", Map.of("client", UNATTENDED_CLIENT_ID)))
                .as("the parent row of the unattended client's authorization").isZero();
    }

    /**
     * Revoking an access token ends the authorization, which is what §撤销 says it does.
     *
     * <p>It only ever happened for the refresh token. {@code POST /oauth/revoke} with an access token
     * marked that one row and stopped, so the refresh token stayed live: a client that gave up the
     * token it was holding was told it had succeeded, and the credential it meant to end went on
     * renewing for months. RFC 7009 does allow revoking a single token, which is why the behaviour
     * looked defensible; the contract here is deliberately stricter, and for the reason
     * {@code TokenStore.revokeAuthorization} gives — "the client logged out" and "the client still
     * works" cannot both be true.
     */
    @Test
    void revokingTheAccessTokenEndsTheAuthorizationToo() {
        seedCliClient();
        registerAndSignIn(randomUsername(), randomPhone());

        String verifier = codeVerifier();
        String code = consentAndGetCode(verifier, "state-" + randomUsername());
        JsonNode issued = body(token(form(Map.of(
                "grant_type", List.of("authorization_code"),
                "code", List.of(code),
                "redirect_uri", List.of(REDIRECT_URI),
                "client_id", List.of(CLIENT_ID),
                "code_verifier", List.of(verifier)))));
        String accessToken = issued.get("access_token").asText();
        String refreshToken = issued.get("refresh_token").asText();
        assertThat(get("/api/v1/skills", accessToken).statusCode())
                .as("the token works before it is revoked").isEqualTo(200);

        HttpResponse<String> revoked = send(formRequest(REVOKE,
                form(Map.of("token", List.of(accessToken), "client_id", List.of(CLIENT_ID)))));
        assertThat(revoked.statusCode()).as("revoking: %s", revoked.body()).isEqualTo(200);

        assertThat(get("/api/v1/skills", accessToken).statusCode()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM refresh_token WHERE revoked_at IS NULL"))
                .as("the refresh token of an authorization that was just ended")
                .isZero();
        HttpResponse<String> refused = token(form(Map.of(
                "grant_type", List.of("refresh_token"),
                "refresh_token", List.of(refreshToken),
                "client_id", List.of(CLIENT_ID))));
        assertThat(refused.statusCode()).as("a refresh after it: %s", refused.body()).isEqualTo(400);
        assertThat(body(refused).get("error").asText()).isEqualTo("invalid_grant");
    }

    /** One rotation, returning the refresh token that replaces the one handed in. */
    private String refresh(String refreshToken) {
        HttpResponse<String> response = token(form(Map.of(
                "grant_type", List.of("refresh_token"),
                "refresh_token", List.of(refreshToken),
                "client_id", List.of(CLIENT_ID))));
        assertThat(response.statusCode()).as("refreshing: %s", response.body()).isEqualTo(200);
        return body(response).get("refresh_token").asText();
    }

    /**
     * The browser half: authorize, approve, and come back with a code.
     *
     * <p><strong>The state posted back is not the one the client sent.</strong> The framework issues its
     * own for the consent round-trip and puts it in the redirect, and the form has to return that one —
     * posting the original is refused with {@code OAuth 2.0 Parameter: state}. A client never sees
     * this, because the round-trip happens in the browser; a test does, and getting it wrong looks
     * like a security failure rather than a wrong parameter.
     */
    private String consentAndGetCode(String verifier, String state) {
        HttpResponse<String> toConsent = browser.get(uri(AUTHORIZE + "?" + form(authorizationQuery(verifier, state))));
        assertThat(toConsent.statusCode()).as("the authorization request: %s", toConsent.body())
                .isEqualTo(302);
        // Absolute rather than relative: the framework builds it from the address the request arrived
        // on, which is the random port this test server is listening on.
        assertThat(URI.create(location(toConsent)).getPath())
                .as("where an unconsented authorization request is sent: %s", location(toConsent))
                .isEqualTo(CONSENT_PAGE);

        String consentState = queryParameter(location(toConsent), "state");
        Map<String, List<String>> approved = Map.of(
                "client_id", List.of(CLIENT_ID),
                "state", List.of(consentState),
                "scope", List.of("skills:read", "skills:write"));

        // The CSRF token is sent because the page sends one, not because it is checked: the framework
        // exempts its own endpoints from CSRF protection in `init()` (`csrf.ignoringRequestMatchers`
        // over the endpoints matcher), and there is no public way to take an endpoint back off that
        // list. The exemption and its bounded risk are recorded in the module doc; what this test
        // pins is that the flow works when the token is present, which is what the page will send.
        HttpResponse<String> toCallback =
                browser.postForm(uri(AUTHORIZE), form(approved), browser.csrfToken());
        assertThat(toCallback.statusCode()).as("the consent submission: %s", toCallback.body())
                .isEqualTo(302);

        String code = queryParameter(location(toCallback), "code");
        assertThat(code).as("the authorization code in %s", location(toCallback)).isNotBlank();
        // The client's own state comes back untouched, which is the whole point of it.
        assertThat(queryParameter(location(toCallback), "state")).isEqualTo(state);
        return code;
    }

    private static Map<String, List<String>> authorizationQuery(String verifier, String state) {
        return Map.of(
                "response_type", List.of("code"),
                "client_id", List.of(CLIENT_ID),
                "redirect_uri", List.of(REDIRECT_URI),
                "scope", List.of("skills:read skills:write"),
                "state", List.of(state),
                "code_challenge", List.of(challenge(verifier)),
                "code_challenge_method", List.of("S256"));
    }

    private HttpResponse<String> token(String body) {
        return send(formRequest(TOKEN, body));
    }

    /** A form-encoded request to this server; the token plane reads nothing else. */
    private HttpRequest formRequest(String path, String body) {
        return HttpRequest.newBuilder(uri(path))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /** A form body, with repeated values kept in order — {@code scope} arrives more than once. */
    private static String form(Map<String, List<String>> fields) {
        List<String> pairs = new ArrayList<>();
        fields.forEach((name, values) -> values.forEach(
                value -> pairs.add(encode(name) + "=" + encode(value))));
        return String.join("&", pairs);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String location(HttpResponse<String> response) {
        return response.headers().firstValue(HttpHeaders.LOCATION)
                .orElseThrow(() -> new AssertionError("no Location header on " + response.statusCode()
                        + "\nheaders: " + response.headers().map()
                        + "\nbody: " + response.body()));
    }

    private static String queryParameter(String uri, String name) {
        for (String pair : URI.create(uri).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) {
                return java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Every token value currently on disk, from all three tables. */
    private List<String> storedTokenValues() {
        return jdbc.sql("SELECT code_hash AS value FROM auth_code"
                        + " UNION ALL SELECT token_hash FROM access_token"
                        + " UNION ALL SELECT token_hash FROM refresh_token")
                .query(String.class)
                .list();
    }

    // ------------------------------------------------------------------
    // PKCE
    // ------------------------------------------------------------------

    /** 43 characters, which is RFC 7636's floor, from 32 bytes of entropy. */
    private static String codeVerifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
