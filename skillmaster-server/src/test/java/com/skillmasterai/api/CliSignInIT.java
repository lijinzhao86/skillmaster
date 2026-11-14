package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAuthorizationCodeIT;
import com.skillmasterai.support.Browser;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * v68's CLI login over real HTTP: the question, the two answers, the poll, and the ways a question
 * can end — its own row, a repeated login on the same machine, the clock, and the button that ends
 * every other device at once.
 *
 * <p><strong>Both planes are in one class because one login is on both.</strong> A session answers
 * the question, a client with no session collects it, and what comes out is spent on the API plane —
 * so a test of any one leg would keep passing with the other two disconnected.
 *
 * <p><strong>Asserted through the addresses a client uses.</strong> The ticket and the device secret
 * come out of the start's answer rather than out of the table they were hashed into — a question that
 * reached the table without reaching the CLI was never asked.
 *
 * <p><strong>The ways a collection can fail are one case on purpose.</strong> Unticketed, wrong
 * secret and wrong verifier all answer identically, which is a security property rather than three
 * coincidences.
 *
 * <p>{@code @Sql} on the class, like every other test that reaches the browser plane:
 * {@code cli_device_sign_in} and {@code oauth_authorization} are in that script, so a case counting
 * rows counts its own and not the previous case's. {@code oauth_client} is deliberately not, which is
 * why {@link #seedCliClient()} is idempotent.
 */
@Sql("/sql/truncate-business-tables.sql")
class CliSignInIT extends AbstractAuthorizationCodeIT {

    private static final String ME = "/api/v1/me";
    private static final String CLI_WEB = "/web/cli-sign-ins";

    /** A well-formed ticket and secret that name nothing. Shaped like real ones, so refusals compare. */
    private static final String NEVER_ISSUED = "01M4ZS63TDCQEB3VVVVBAK1FRX";

    @Test
    void aMachineCanAskWithoutASessionAndGetsNoCredential() {
        seedCliClient();

        HttpResponse<String> started = startRequest(MACHINE, REGISTERED_SCOPES, codeVerifier());

        assertThat(started.statusCode()).as("asking: %s", started.body()).isEqualTo(201);
        JsonNode body = body(started);
        assertThat(body.get("ticket").asText()).isNotBlank();
        assertThat(body.get("device_secret").asText()).isNotBlank();
        assertThat(body.get("expires_in").asLong()).isEqualTo(600);
        assertThat(body.get("interval").asLong()).isEqualTo(1);
        assertThat(body.has("access_token")).as("a question is not a credential: %s", started.body())
                .isFalse();
        assertThat(count("SELECT count(*) FROM oauth_authorization")).isZero();
    }

    @Test
    void thePageReadsTheQuestionWithoutAnsweringIt() {
        begin();
        Pending pending = start(MACHINE);

        HttpResponse<String> view = send(request(DEVICE_SIGN_INS + "/" + pending.ticket(), null).GET()
                .build());
        assertThat(view.statusCode()).as("reading the question: %s", view.body()).isEqualTo(200);
        JsonNode seen = body(view);
        assertThat(seen.get("status").asText()).isEqualTo("pending");
        assertThat(seen.get("name").asText()).isEqualTo(MACHINE);
        assertThat(seen.get("client").asText()).isEqualTo(CLIENT_ID);
        assertThat(seen.get("scopes").toString()).contains("skills:read", "skills:write");
        assertThat(view.headers().firstValue(HttpHeaders.CACHE_CONTROL).orElse(""))
                .as("a page a chat client may preview must not be kept").isEqualTo("no-store");

        // Reading it twice changes nothing, and the poll still says the question is unanswered.
        send(request(DEVICE_SIGN_INS + "/" + pending.ticket(), null).GET().build());
        assertThat(body(poll(pending)).get("status").asText()).isEqualTo("pending");
    }

    @Test
    void answeringWritesTheAuthorizationAndTheMachineCollectsItOnce() {
        begin();
        Pending pending = start(MACHINE);

        HttpResponse<String> approved = approve(pending);
        assertThat(approved.statusCode()).as("approving: %s", approved.body()).isEqualTo(204);
        assertThat(body(send(request(DEVICE_SIGN_INS + "/" + pending.ticket(), null).GET()
                .build())).get("status").asText()).isEqualTo("approved");

        HttpResponse<String> collected = poll(pending);
        assertThat(collected.statusCode()).as("collecting: %s", collected.body()).isEqualTo(200);
        JsonNode credential = body(collected);
        assertThat(credential.get("status").asText()).isEqualTo("approved");
        String token = credential.get("access_token").asText();
        assertThat(token).isNotBlank();
        assertThat(get(ME, token).statusCode()).as("the credential is usable").isEqualTo(200);
        assertThat(credential.get("name").asText()).isEqualTo(MACHINE);
        assertThat(credential.get("scope").asText()).isEqualTo(REGISTERED_SCOPES);
        assertThat(count("SELECT count(*) FROM oauth_authorization")).isEqualTo(1);

        // Once, and the interval has to pass before the second attempt is even looked at.
        pause();
        assertThat(body(poll(pending)).get("status").asText()).isEqualTo("used");
    }

    @Test
    void aPollBeforeTheAnswerSaysPendingAndTheNextOneCollects() throws InterruptedException {
        begin();
        Pending pending = start(MACHINE);

        assertThat(poll(pending).statusCode()).as("not yet").isEqualTo(202);
        approve(pending);
        Thread.sleep(1_100);
        HttpResponse<String> collected = poll(pending);
        assertThat(collected.statusCode()).as("collected after the interval: %s", collected.body())
                .isEqualTo(200);
        assertThat(body(collected).get("access_token").asText()).isNotBlank();
    }

    /**
     * Ctrl-C ends the question, not only the wait (PRD 验收 #3): a link opened afterwards says it is
     * over, and the poll says the same. Without the cancellation the row would stay pending, and an
     * approval arriving later would authorize a login its own machine had already abandoned.
     */
    @Test
    void cancellingEndsTheQuestionForThePageAndThePoll() {
        begin();
        Pending pending = start(MACHINE);

        HttpResponse<String> cancelled = cancel(pending);
        assertThat(cancelled.statusCode()).as("cancelling: %s", cancelled.body()).isEqualTo(204);

        HttpResponse<String> page = send(request(DEVICE_SIGN_INS + "/" + pending.ticket(), null)
                .GET().build());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(body(page).get("status").asText()).isEqualTo("denied");

        // The page can no longer approve it, and the CLI can no longer collect one.
        assertThat(approve(pending).statusCode()).isEqualTo(409);
        assertThat(poll(pending).statusCode()).isEqualTo(410);
    }

    /** The secret is what proves it; a link alone cannot end somebody else's question. */
    @Test
    void cancellingWithTheWrongSecretNamesNothing() {
        begin();
        Pending pending = start(MACHINE);

        HttpResponse<String> response = send(request(
                        DEVICE_SIGN_INS + "/" + pending.ticket() + "/cancel", null)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                        "device_secret", NEVER_ISSUED))))
                .build());

        assertThat(response.statusCode()).isEqualTo(404);
        // And the question is untouched: the person can still answer it.
        assertThat(approve(pending).statusCode()).isEqualTo(204);
    }

    /** The one-second interval, asserted where a client would feel it: a second immediate poll. */
    @Test
    void pollingFasterThanTheIntervalIsRefusedWithRetryAfter() {
        begin();
        Pending pending = start(MACHINE);
        assertThat(poll(pending).statusCode()).isEqualTo(202);

        HttpResponse<String> tooSoon = poll(pending);

        // The interval is stored at millisecond precision, so "immediately after" is the same answer
        // on every run rather than one that depends on where in the second the first poll landed.
        assertThat(tooSoon.statusCode()).as("asked again immediately: %s", tooSoon.body())
                .isEqualTo(429);
        assertThat(tooSoon.headers().firstValue("Retry-After")).isPresent();
        assertThat(body(tooSoon).get("error").get("code").asText()).isEqualTo("too_many_requests");
    }

    @Test
    void noTicketAWrongSecretAndAWrongVerifierAreOneAnswer() {
        begin();
        Pending pending = start(MACHINE);
        approve(pending);

        HttpResponse<String> noTicket = send(request(DEVICE_SIGN_INS + "/" + NEVER_ISSUED + "/token",
                null)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                        "device_secret", pending.deviceSecret(), "code_verifier", pending.verifier()))))
                .build());
        assertThat(noTicket.statusCode()).as("a ticket that names nothing").isEqualTo(404);

        HttpResponse<String> wrongSecret = send(request(
                DEVICE_SIGN_INS + "/" + pending.ticket() + "/token", null)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                        "device_secret", "not-the-secret", "code_verifier", pending.verifier()))))
                .build());
        assertThat(wrongSecret.statusCode()).as("the wrong process").isEqualTo(404);

        HttpResponse<String> wrongVerifier = send(request(
                DEVICE_SIGN_INS + "/" + pending.ticket() + "/token", null)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                        "device_secret", pending.deviceSecret(), "code_verifier", codeVerifier()))))
                .build());
        assertThat(wrongVerifier.statusCode()).as("the wrong proof").isEqualTo(404);
        assertThat(count("SELECT count(*) FROM access_token")).isZero();
    }

    /**
     * **The verifier is refused before the answer arrives.** Checked only at collection time, a
     * caller holding a leaked device secret could watch a pending question turn denied or expired —
     * and, worse, the second factor would be missing during exactly the window it protects.
     */
    @Test
    void aWrongVerifierIsRefusedWhileTheQuestionIsStillPending() {
        begin();
        Pending pending = start(MACHINE);

        HttpResponse<String> wrong = send(request(DEVICE_SIGN_INS + "/" + pending.ticket() + "/token",
                null)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                        "device_secret", pending.deviceSecret(),
                        "code_verifier", codeVerifier()))))
                .build());

        assertThat(wrong.statusCode()).as("the wrong proof: %s", wrong.body()).isEqualTo(404);
        // The refused poll spent the one-second interval, so the honest collection waits it out.
        pause();
        // The question itself is untouched: the real process can still collect after approval.
        redeem(pending);
    }

    /**
     * An approval clicked in the last second still delivers. The ten minutes bound the person's
     * decision; the CLI asks once a second, so without this ordering that approval would be answered
     * as expired forever, with the authorization row written and no credential behind it.
     */
    @Test
    void anApprovalMadeInTimeIsStillCollectableAfterTheClockRunsOut() {
        begin();
        Pending pending = start(MACHINE);
        assertThat(approve(pending).statusCode()).isEqualTo(204);
        // The question's clock moves past its end after the approval landed.
        jdbc.sql("UPDATE cli_device_sign_in SET expires_at = '2000-01-01T00:00:00Z'"
                + " WHERE ticket_hash = :hash").param("hash", hashOf(pending.ticket())).update();

        assertThat(poll(pending).statusCode()).as("the approved login is still owed").isEqualTo(200);
    }

    /**
     * **The cancellation that lost the race ends what the approval wrote.** Ctrl-C arriving just
     * after the person's click would otherwise leave an authorization whose only collector has
     * exited — live, and invisible in 「登录设备」 because no token was ever minted.
     */
    @Test
    void cancellingAfterTheApprovalEndsTheUncollectedLogin() {
        begin();
        Pending pending = start(MACHINE);
        assertThat(approve(pending).statusCode()).isEqualTo(204);

        assertThat(cancel(pending).statusCode()).isEqualTo(204);

        assertThat(count("SELECT count(*) FROM oauth_authorization WHERE revoked_at IS NULL"))
                .as("the login nobody collected is ended").isZero();
        assertThat(poll(pending).statusCode()).isEqualTo(410);
    }

    /**
     * **The CLI's clock starts at the answer to `start`, a round trip after the server wrote
     * `expires_at`.** So a Ctrl-C pressed at the very end of the wait can arrive after the row's own
     * clock; an approval that landed before it must still be tidied, or the page says "the device
     * will continue" about a machine that has already gone.
     */
    @Test
    void cancellingAfterTheClockRanOutStillEndsAnUncollectedApproval() {
        begin();
        Pending pending = start(MACHINE);
        assertThat(approve(pending).statusCode()).isEqualTo(204);
        jdbc.sql("UPDATE cli_device_sign_in SET expires_at = '2000-01-01T00:00:00Z'"
                + " WHERE ticket_hash = :hash").param("hash", hashOf(pending.ticket())).update();

        assertThat(cancel(pending).statusCode()).isEqualTo(204);

        assertThat(count("SELECT count(*) FROM oauth_authorization WHERE revoked_at IS NULL"))
                .as("the uncollected approval is ended even after the question's clock").isZero();
        assertThat(body(send(request(DEVICE_SIGN_INS + "/" + pending.ticket(), null).GET()
                .build())).get("status").asText()).isEqualTo("denied");
    }

    @Test
    void aRefusalWritesNothingAndEndsTheQuestion() {
        begin();
        Pending pending = start(MACHINE);
        long before = count("SELECT count(*) FROM oauth_authorization");

        HttpResponse<String> refused = refuse(pending);
        assertThat(refused.statusCode()).as("refusing: %s", refused.body()).isEqualTo(204);
        assertThat(body(send(request(DEVICE_SIGN_INS + "/" + pending.ticket(), null).GET()
                .build())).get("status").asText()).isEqualTo("denied");
        assertThat(body(poll(pending)).get("status").asText()).isEqualTo("denied");
        assertThat(count("SELECT count(*) FROM oauth_authorization")).isEqualTo(before);
    }

    @Test
    void anExpiredQuestionCannotBeAnsweredOrCollected() {
        begin();
        Pending pending = start(MACHINE);
        jdbc.sql("UPDATE cli_device_sign_in SET expires_at = '2000-01-01T00:00:00Z'"
                + " WHERE ticket_hash = :hash").param("hash", hashOf(pending.ticket())).update();

        assertThat(body(send(request(DEVICE_SIGN_INS + "/" + pending.ticket(), null).GET()
                .build())).get("status").asText()).isEqualTo("expired");
        assertThat(approve(pending).statusCode()).as("too late to answer").isEqualTo(409);
        assertThat(body(poll(pending)).get("status").asText()).isEqualTo("expired");
        assertThat(count("SELECT count(*) FROM oauth_authorization")).isZero();
    }

    @Test
    void theRequestedScopesAreIntersectedWithTheClientsAndNeverWidened() {
        begin();

        // One scope the client is registered for, beside one it is not — and `skills:admin` is not in
        // the server's vocabulary either, which is the point: the intersection is with the registered
        // row and nothing else was consulted.
        Pending pending = start(MACHINE, "skills:read skills:admin");
        assertThat(approve(pending).statusCode()).isEqualTo(204);
        HttpResponse<String> collected = poll(pending);
        assertThat(collected.statusCode()).as("collecting: %s", collected.body()).isEqualTo(200);
        assertThat(body(collected).get("scope").asText())
                .as("the granted set, which is not the requested set")
                .isEqualTo("skills:read");

        String token = body(collected).get("access_token").asText();
        HttpResponse<String> write = send(request("/api/v1/skills", token)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
        assertThat(write.statusCode())
                .as("a credential granted read alone writing: %s", write.body())
                .isEqualTo(403);
        assertThat(body(write).get("error").get("code").asText()).isEqualTo("insufficient_scope");
    }

    @Test
    void endingALoginIsReflectedOnTheCredentialsNextRequest() {
        begin();
        String token = redeem(start(MACHINE));
        assertThat(get(ME, token).statusCode()).isEqualTo(200);

        HttpResponse<String> ended =
                webPost(CLI_WEB + "/" + authorizationId() + "/revoke", "", csrfToken());
        assertThat(ended.statusCode()).as("ending the login: %s", ended.body()).isEqualTo(204);

        HttpResponse<String> refused = get(ME, token);
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(body(refused).get("error").get("code").asText())
                .as("a login somebody ended, not a credential that matches nothing")
                .isEqualTo("token_revoked");
    }

    @Test
    void theSameMachineNameEndsTheLoginItReplaces() {
        begin();
        String first = redeem(start(MACHINE));
        String second = redeem(start(MACHINE));

        assertThat(get(ME, first).statusCode()).as("the login the second one replaced").isEqualTo(401);
        assertThat(get(ME, second).statusCode()).as("the login that replaced it").isEqualTo(200);
    }

    @Test
    void theConsoleListsTheLoginAndOnlyItsOwnerCanEndIt() {
        begin();
        redeem(start(MACHINE));
        String mine = authorizationId();

        List<JsonNode> rows = rowsOfKind(sessionsOf(), "cli");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("device").asText()).isEqualTo("CLI · " + MACHINE);

        Browser intruder = registerAnotherAccount(randomUsername(), randomPhone());
        HttpResponse<String> notMine = intruder.post(uri(CLI_WEB + "/" + mine + "/revoke"), "",
                intruder.csrfToken());
        assertThat(notMine.statusCode()).as("somebody else's login").isEqualTo(404);
        HttpResponse<String> unknown = intruder.post(uri(CLI_WEB + "/" + NEVER_ISSUED + "/revoke"), "",
                intruder.csrfToken());
        assertThat(unknown.statusCode()).as("a login that never existed").isEqualTo(404);

        assertThat(webPost(CLI_WEB + "/" + mine + "/revoke", "", csrfToken()).statusCode())
                .as("the owner can still end it").isEqualTo(204);
    }

    @Test
    void revokeOthersEndsThisAccountsCliLoginsAndLeavesEveryoneElseAlone() {
        seedCliClient();
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        Browser second = secondSignedInBrowser(phone, PASSWORD);

        String mine = redeem(start(MACHINE));
        assertThat(get(ME, mine).statusCode()).as("the login this case is about").isEqualTo(200);

        Browser other = registerAnotherAccount(randomUsername(), randomPhone());
        Pending theirsQuestion = start("their MacBook");
        assertThat(approveThrough(other, theirsQuestion, "their MacBook").statusCode())
                .as("another account answering a question").isEqualTo(204);
        String theirs = collect(theirsQuestion);
        assertThat(get(ME, theirs).statusCode()).as("another account's login").isEqualTo(200);

        HttpResponse<String> response = webPost(SESSIONS + "/revoke-others", "", csrfToken());
        assertThat(response.statusCode()).as("ending the others: %s", response.body()).isEqualTo(200);
        assertThat(body(response).get("revoked").asInt())
                .as("the number is the sessions M1 ended: the CLI logins go through the other module,"
                        + " which answers nothing by design")
                .isEqualTo(1);

        HttpResponse<String> refused = get(ME, mine);
        assertThat(refused.statusCode()).as("a CLI login is one of the 'other devices'").isEqualTo(401);
        assertThat(body(refused).get("error").get("code").asText())
                .as("ended, not a credential that matches nothing").isEqualTo("token_revoked");
        assertThat(rowsOfKind(sessionsOf(), "cli")).as("the row the sentence was about").isEmpty();

        assertThat(webGet(SESSION).statusCode()).as("the session that asked survives").isEqualTo(200);
        assertThat(second.get(uri(SESSION)).statusCode()).as("the other browser device").isEqualTo(401);
    }

    @Test
    void askingWithoutANameOrAChallengeIsARefusedRequest() {
        seedCliClient();

        HttpResponse<String> noName = startRequest(null, REGISTERED_SCOPES, codeVerifier());
        assertThat(noName.statusCode()).as("no name: %s", noName.body()).isEqualTo(400);

        HttpResponse<String> noChallenge = send(request(DEVICE_SIGN_INS, null)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                        "name", MACHINE, "client_id", CLIENT_ID, "scope", REGISTERED_SCOPES))))
                .build());
        assertThat(noChallenge.statusCode()).as("no challenge: %s", noChallenge.body()).isEqualTo(400);
    }

    @Test
    void answeringWithoutANameIsARefusedRequest() {
        begin();
        Pending pending = start(MACHINE);

        HttpResponse<String> refused = webPost(DEVICE_APPROVALS + "/" + pending.ticket() + "/approve",
                JSON.writeValueAsString(Map.of("name", "  ")), csrfToken());
        assertThat(refused.statusCode()).as("a blank name: %s", refused.body()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM oauth_authorization")).isZero();
    }

    @Test
    void answeringWithoutASessionIsRefusedAndNothingIsRecorded() {
        seedCliClient();
        Pending pending = start(MACHINE);
        Browser stranger = new Browser();
        stranger.get(uri(SESSION));

        HttpResponse<String> refused = stranger.post(uri(DEVICE_APPROVALS + "/" + pending.ticket()
                + "/approve"), JSON.writeValueAsString(Map.of("name", MACHINE)), stranger.csrfToken());

        assertThat(refused.statusCode()).as("a session-less answer: %s", refused.body()).isEqualTo(401);
        assertThat(wwwAuthenticate(refused)).isNull();
        assertThat(count("SELECT count(*) FROM oauth_authorization")).isZero();
    }

    /** A second person, in their own browser — the account an intruder check needs. */
    private Browser registerAnotherAccount(String username, String phone) {
        Browser other = new Browser();
        // A fresh client has no CSRF token until it has read something, exactly as a browser does.
        other.get(uri(SESSION));
        HttpResponse<String> created = other.post(uri(REGISTER), json(Map.of(
                "phone", phone, "code", requestCode(phone),
                "password", PASSWORD, "username", username, "consent", true)), other.csrfToken());
        assertThat(created.statusCode())
                .as("registering %s: %s", username, created.body())
                .isEqualTo(201);
        return other;
    }

    /** The per-ticket interval is one second; a case that polls twice waits it out. */
    private static void pause() {
        try {
            Thread.sleep(1_100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The ticket's hash as the table stores it — used only to make an expiry happen in one case. */
    private static String hashOf(String value) {
        return com.skillmasterai.common.Sha256Hex.of(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
