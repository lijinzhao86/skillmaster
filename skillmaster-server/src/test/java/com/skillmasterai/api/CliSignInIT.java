package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.support.AbstractAuthorizationCodeIT;
import com.skillmasterai.support.Browser;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * v4's CLI login over real HTTP: the click, the code it carries, the credential that comes back, and
 * the ways a login can end — its own row, a repeated login on the same machine, the clock, and the
 * button that ends every other device at once (v4 Q6).
 *
 * <p><strong>Both planes are in one class because one login is on both.</strong> A session starts it,
 * a client with no session redeems it, and what comes out is spent on the API plane — so a test of any
 * one leg would keep passing with the other two disconnected. That is also why the base class is
 * {@link AbstractAuthorizationCodeIT}: this feature needs the seeded {@code oauth_client} row, and its
 * PKCE challenge is checked against the same helper the third-party flow uses.
 *
 * <p><strong>Asserted through the addresses a client uses.</strong> The code is read out of the
 * {@code redirect_to} the start answered with rather than out of the table it was written to — a code
 * that reached the table without reaching that address was never delivered. The one thing read from a
 * row is the id of a login to end, because the endpoint that lists logins is the console's 「登录设备」
 * screen and that screen is P1; the id is the same value the console will address, and the write that
 * spends it goes through the route the console calls.
 *
 * <p><strong>The four exchange failures are one case on purpose.</strong> That an unknown code, an
 * expired code, a spent code and a wrong verifier all answer identically is a security property rather
 * than four coincidences, and four separate cases would let one of them drift into a fifth answer with
 * no single test noticing.
 *
 * <p>{@code @Sql} on the class, like every other test that reaches the browser plane:
 * {@code cli_sign_in_code} and {@code oauth_authorization} are in that script, so a case counting rows
 * counts its own and not the previous case's. {@code oauth_client} is deliberately not, which is why
 * {@link #seedCliClient()} is idempotent.
 */
@Sql("/sql/truncate-business-tables.sql")
class CliSignInIT extends AbstractAuthorizationCodeIT {

    /** v4's three addresses, written out for the reason the constants above are. */
    private static final String SIGN_INS = "/web/cli-sign-ins";
    private static final String EXCHANGE = "/cli-sign-ins/exchange";
    private static final String ME = "/api/v1/me";

    /** The console's list, which since v4 answers for both kinds of login (v4 Q6). */
    private static final String SESSIONS = "/web/sessions";

    /** What the CLI asks for, and what the seeded client row is registered to allow. */
    private static final String REGISTERED_SCOPES = "skills:read skills:write";

    /** The state the CLI generated and the screen read off its own address. Handed back unread. */
    private static final String STATE = "state-from-the-cli";

    private static final String MACHINE = "my MacBook";

    /**
     * A well-formed id that names nothing. Shaped like a ULID because the real ones are, and because a
     * refusal is only the same refusal if the two requests are equally well-formed.
     */
    private static final String NEVER_ISSUED = "01M4ZS63TDCQEB3VVVVBAK1FRX";

    /**
     * A code that names nothing, and is not shaped like one either.
     *
     * <p>Well-formedness buys nothing here, and that is a fact about the exchange rather than a
     * shortcut: a code is stored as a digest, so a guess and a real code are the same amount of work
     * and the only thing the endpoint could ever learn from one is whether a row matches. Each attempt
     * appends its own number, so no two requests in the run carry the same string.
     */
    private static final String NEVER_ISSUED_CODE = "a-code-this-server-never-issued";

    /**
     * How many refusals the limit's case will spend before it calls the limit missing.
     *
     * <p>The rule allows sixty per address per fifteen minutes (v4 §接口), and this is above twice
     * that: a run unlucky enough to straddle one of the fixed window boundaries — where the counter
     * starts again — still reaches a refusal rather than ending on the bound. It is here to bound the
     * loop, not to pin the number; what the case asserts is that one address is refused at all, and
     * that the refusal cannot be told which code arrived.
     */
    private static final int ATTEMPTS_BEFORE_THE_LIMIT_MUST_FIRE = 150;

    // -------------------------------------------------------------------- the login, end to end

    /**
     * T1's server half: the click produces a code, the code produces a credential, and that credential
     * is accepted on the API plane — with the three things the terminal prints for it.
     *
     * <p><strong>The last step is what makes the rest a claim about a credential rather than about a
     * string.</strong> Everything before it would hold if the exchange answered with any 43 random
     * characters; {@code /api/v1/me} accepting them, and naming the account the browser was signed in
     * as, is the difference.
     *
     * <p><strong>The name is one of those three, and it is the only one the client could not
     * otherwise have.</strong> 它的名字 / 权限 / 什么时候到期 (prd 验收 #1): the scopes and the expiry are
     * properties of the credential, while the name was typed on the browser's screen and is nowhere in
     * what the CLI holds. So it is asserted here rather than left to the CLI, which would have nothing
     * to print and no honest way to fill the gap.
     */
    @Test
    void aLoginStartedInTheBrowserEndsAsACredentialThatAuthenticates() {
        String userId = begin();
        String verifier = codeVerifier();

        HttpResponse<String> started = startRequest(MACHINE, REGISTERED_SCOPES, verifier);
        assertThat(started.statusCode()).as("starting a login: %s", started.body()).isEqualTo(201);

        // The address carries the code and the caller's own state, and nothing else. Whatever else
        // were on it would land in a browser history, a Referer and a log — which is the whole reason
        // the code travels here instead of the credential.
        String redirect = redirectOf(started);
        assertThat(parameterNamesOf(redirect)).as("what %s carries", redirect)
                .containsExactlyInAnyOrder("code", "state");
        assertThat(queryParameter(redirect, "state")).isEqualTo(STATE);
        assertThat(body(started).has("access_token"))
                .as("the credential is minted at the exchange, so it cannot be in this answer")
                .isFalse();

        HttpResponse<String> exchanged = exchange(queryParameter(redirect, "code"), verifier);
        assertThat(exchanged.statusCode()).as("redeeming: %s", exchanged.body()).isEqualTo(200);
        JsonNode credential = body(exchanged);
        String token = credential.get("access_token").asText();
        assertThat(token).isNotBlank();
        // The granted set, and compared as a set: the contract is which scopes, and the field is the
        // one the credential itself carries, not a string a client may compare.
        assertThat(credential.get("scope").asText().split(" "))
                .containsExactlyInAnyOrder("skills:read", "skills:write");
        assertThat(credential.get("expires_at").asText()).as("when it dies unused, RFC3339 UTC")
                .isNotBlank();
        assertThat(credential.get("name").asText())
                .as("what the person typed for this machine, which is what the terminal prints")
                .isEqualTo(MACHINE);

        HttpResponse<String> me = get(ME, token);
        assertThat(me.statusCode()).as("using the new credential: %s", me.body()).isEqualTo(200);
        assertThat(body(me).get("user").get("user_id").asText()).isEqualTo(userId);
    }

    /**
     * T13's second half and T7's: the row holds a digest, and the plaintext is in no row at all.
     *
     * <p><strong>The credential is not a special case of anything — it is hashed like the other three
     * kinds of secret</strong>, which is ADR 0007's rule and the reason a database leak is not a
     * credential leak. Asserting that the row is <em>a</em> 64-character string would not say that;
     * recomputing the digest over the credential the exchange handed back does.
     *
     * <p><strong>And then the search, because "hashed in the table we thought of" is not the
     * property.</strong> One statement walks every column this version writes a value to, including
     * the audit trail — which records that a login happened and must not record what it produced.
     */
    @Test
    void theCredentialIsStoredAsADigestAndItsPlaintextIsInNoRow() {
        begin();
        Pending pending = start(MACHINE);
        String token = redeem(pending);

        String stored = jdbc.sql("SELECT token_hash FROM access_token").query(String.class).single();
        assertThat(stored).as("the credential is stored as its sha256, and that is the only form")
                .isEqualTo(Sha256Hex.of(token.getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(token);
        assertThat(stored).hasSize(64);

        // The code is a digest too, and a different one: it delivered the credential, it does not
        // stand in for one.
        assertThat(jdbc.sql("SELECT code_hash FROM cli_sign_in_code").query(String.class).single())
                .isEqualTo(Sha256Hex.of(pending.code().getBytes(StandardCharsets.UTF_8)));

        assertThat(rowsContaining(token)).as("rows carrying the credential in the clear").isZero();
        assertThat(rowsContaining(pending.code())).as("rows carrying the code in the clear").isZero();
    }

    /**
     * Four ways a code can be unusable, one answer — T13(a)–(d), and the rule in §接口.
     *
     * <p><strong>The collapse is protection for whoever is holding a random string.</strong> "You
     * already spent that code" is the more useful sentence, and it is also the one that confirms the
     * string was once real. Asserting all four together is what makes them comparable: one answer means
     * the same status and the same code, and the case is written so that a fifth answer for any one of
     * them fails here rather than nowhere.
     */
    @Test
    void theFourWaysACodeCanBeUnusableAreOneAnswer() {
        begin();
        Pending spent = start(MACHINE);
        Pending expired = start("the build box");
        Pending mismatched = start("the spare");

        // What makes the spent one spent is the redemption this case is about, done once here.
        assertThat(exchange(spent.code(), spent.verifier()).statusCode()).isEqualTo(200);
        expire(expired.code());

        Map<String, HttpResponse<String>> answers = new LinkedHashMap<>();
        answers.put("a code that was never issued",
                exchange("not-a-code-this-server-ever-issued", codeVerifier()));
        answers.put("a code already spent", exchange(spent.code(), spent.verifier()));
        answers.put("a code whose time has passed", exchange(expired.code(), expired.verifier()));
        answers.put("a code with the wrong verifier", exchange(mismatched.code(), codeVerifier()));

        answers.forEach((what, answer) -> {
            assertThat(answer.statusCode()).as("%s: %s", what, answer.body()).isEqualTo(404);
            assertThat(body(answer).get("error").get("code").asText())
                    .as("%s must not be told apart", what)
                    .isEqualTo("code_not_found");
        });
        assertThat(answers.values().stream().map(HttpResponse::body).distinct().count())
                .as("four answers, one sentence: %s",
                        answers.values().stream().map(HttpResponse::body).toList())
                .isEqualTo(1);
    }

    /**
     * A code is spent by the first redemption, and the failed second try does not touch what the first
     * one produced.
     *
     * <p>The second half is the half worth asserting: a retry that reissued, invalidated or otherwise
     * disturbed the live credential would be worse than one that merely succeeded again, and it would
     * pass a test that only looked at the second answer.
     */
    @Test
    void aCodeIsSpentByTheFirstRedemptionAndRefusedAfterIt() {
        begin();
        Pending pending = start(MACHINE);
        String token = redeem(pending);
        assertThat(get(ME, token).statusCode()).isEqualTo(200);

        HttpResponse<String> again = exchange(pending.code(), pending.verifier());

        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(body(again).get("error").get("code").asText()).isEqualTo("code_not_found");
        assertThat(get(ME, token).statusCode()).as("the credential from the first redemption")
                .isEqualTo(200);
        assertThat(count("SELECT count(*) FROM access_token")).as("one redemption, one credential")
                .isEqualTo(1);
    }

    /**
     * T14 · the limit the exchange carries for itself, and the probe it must not turn into.
     *
     * <p><strong>The property is not "there is a limit" but "the limit cannot be read".</strong> This is
     * the one write on this server a caller with no session may make, and it answers a question about a
     * secret — so a refusal that depended on the code would be worth more to whoever is guessing than
     * the work it saves: "throttled" for a code that exists and "throttled" for one that does not is one
     * sentence, while a difference between them is an oracle that confirms a guessed code. T13 closes
     * that hole in the four ways a code can be unusable; this case is the layer above it, where the
     * count is taken before the code is looked at at all.
     *
     * <p><strong>A live code is what makes "cannot be read" observable.</strong> Hammering with garbage
     * alone would prove only that garbage is refused. The last two requests are the same request with a
     * different code in it — one this exchange would otherwise accept, one that names nothing — and the
     * assertion is that the two answers are the same answer, the same status and the same bytes as the
     * refusal that ended the run.
     *
     * <p><strong>Where the count is kept is asserted from the rows, and by key rather than by count.</strong>
     * One counter per caller is what makes the limit an address's budget; a row per code would be the
     * oracle again, written down. The windows are fixed intervals and a run may in principle cross one,
     * so the reading is the number of distinct keys — one caller, whatever number of windows it took.
     */
    @Test
    void theExchangeLimitRefusesARealCodeAndARandomOneIdentically() {
        begin();
        // A live login, deliberately not redeemed: it is the control for the claim below, and the one
        // code in this case that the exchange would accept.
        Pending live = start(MACHINE);

        // The plan's step, done literally — one caller, codes that name nothing, until it is refused.
        HttpResponse<String> firstRefusal = null;
        int attempts = 0;
        while (firstRefusal == null && attempts < ATTEMPTS_BEFORE_THE_LIMIT_MUST_FIRE) {
            attempts++;
            HttpResponse<String> answer = exchange(NEVER_ISSUED_CODE + "-" + attempts, codeVerifier());
            if (answer.statusCode() == 429) {
                firstRefusal = answer;
            } else {
                assertThat(answer.statusCode())
                        .as("attempt %s, a code that names nothing: %s", attempts, answer.body())
                        .isEqualTo(404);
            }
        }
        assertThat(firstRefusal)
                .as("one address submitting codes that name nothing has to be refused within %s"
                        + " attempts, and the %sth was not", ATTEMPTS_BEFORE_THE_LIMIT_MUST_FIRE,
                        attempts)
                .isNotNull();

        HttpResponse<String> givenALiveCode = exchange(live.code(), live.verifier());
        HttpResponse<String> givenANonsenseOne =
                exchange(NEVER_ISSUED_CODE + "-" + (attempts + 1), codeVerifier());
        List<HttpResponse<String>> refusals = List.of(firstRefusal, givenALiveCode, givenANonsenseOne);

        for (HttpResponse<String> refused : refusals) {
            assertThat(refused.statusCode()).isEqualTo(429);
            assertThat(body(refused).get("error").get("code").asText())
                    .isEqualTo("too_many_requests");
            String retryAfter = refused.headers().firstValue("Retry-After").orElse(null);
            assertThat(retryAfter)
                    .as("a 429 without Retry-After invites the retry loop it exists to stop")
                    .isNotNull();
            // The window is fifteen minutes; a value past that would mean the counter has lost track of
            // which window it is counting in.
            assertThat(Long.parseLong(retryAfter)).isBetween(1L, 900L);
        }
        // Byte equality rather than two matching fields: anything at all that the code could have moved
        // in this answer — a word, a hint, a detail — is a difference somebody could act on.
        assertThat(refusals.stream().map(HttpResponse::body).distinct().count())
                .as("one sentence for a live code, one that names nothing, and the one that ended the"
                        + " run: %s", refusals.stream().map(HttpResponse::body).toList())
                .isEqualTo(1);

        // The same fact read off the row instead of the answer: the live code is still there to be
        // redeemed, so the refused exchange did not spend it on its way to refusing.
        assertThat(count("SELECT count(*) FROM cli_sign_in_code WHERE used_at IS NOT NULL"))
                .as("a refused exchange must not have spent the code it was refused for")
                .isZero();

        // And the counter belongs to the caller. Distinct keys and not rows, because the window is a
        // fixed interval and a run may cross one: what must be true either way is that every attempt
        // from this machine was counted against the same thing. One key per code would be an oracle
        // with a table behind it.
        assertThat(count("SELECT count(DISTINCT key_hash) FROM auth_throttle"
                        + " WHERE scope = 'cli-sign-in:exchange'"))
                .as("the rule is keyed by the caller's address: %s codes were tried from one machine",
                        attempts + 2)
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------- what the server decides

    /**
     * The name is required, and the server is what enforces it.
     *
     * <p><strong>The screen disables the button while the field is empty, and that stops a typo and
     * nothing else</strong> — the endpoint is reachable without the page. A blank name is not cosmetic
     * either: it is how a repeated login finds the machine it replaces (Q7), so every unnamed machine
     * would look like the same machine and end the others' logins.
     */
    @Test
    void aBlankNameIsRefusedByTheServerAndNotOnlyByTheScreen() {
        begin();

        for (String blank : List.of("", "   ")) {
            HttpResponse<String> refused = startRequest(blank, REGISTERED_SCOPES, codeVerifier());
            assertThat(refused.statusCode()).as("a name of \"%s\": %s", blank, refused.body())
                    .isEqualTo(400);
            assertThat(body(refused).get("error").get("code").asText()).isEqualTo("invalid_request");
        }

        // And absent altogether: the controller's check is `name == null || name.isBlank()`, and the
        // two halves are two failures a screen prevents in one way and a hand-built body reaches in
        // two.
        HttpResponse<String> absent = startRequest(null, REGISTERED_SCOPES, codeVerifier());
        assertThat(absent.statusCode()).as("no name at all: %s", absent.body()).isEqualTo(400);
        assertThat(body(absent).get("error").get("code").asText()).isEqualTo("invalid_request");

        // Nothing was recorded on the way to the refusal, so a refused start leaves no login behind.
        assertThat(count("SELECT count(*) FROM oauth_authorization")).isZero();
        assertThat(count("SELECT count(*) FROM cli_sign_in_code")).isZero();
    }

    /**
     * Where the browser goes next is read from the registered client row, and the request cannot move
     * it.
     *
     * <p><strong>A redirect target chosen by the caller is an open redirect with a one-time code on
     * the end of it</strong>, and a loopback address is no exception: the server would still be handing
     * a code to an address nobody registered. So this case sends the field anyway — the body's text is
     * the caller's — and asserts both that it had no effect and that it stayed out of the answer.
     */
    @Test
    void theRedirectTargetComesFromTheRegisteredClientNotFromTheRequest() {
        begin();
        String verifier = codeVerifier();

        Map<String, Object> edited = new HashMap<>();
        edited.put("name", MACHINE);
        edited.put("client_id", CLIENT_ID);
        edited.put("scope", REGISTERED_SCOPES);
        edited.put("state", STATE);
        edited.put("code_challenge", challenge(verifier));
        // The field the design deliberately has no room for. ADR 0028's fixed loopback port is what
        // makes the address a deployment fact rather than a request parameter.
        edited.put("redirect_uri", "https://somewhere-else.test/callback");

        HttpResponse<String> started = webPost(SIGN_INS, json(edited), csrfToken());
        assertThat(started.statusCode())
                .as("starting with a redirect_uri in the body: %s", started.body())
                .isEqualTo(201);

        String redirect = redirectOf(started);
        assertThat(redirect).doesNotContain("somewhere-else");

        // And the delivery still works, to the address that was registered: a field the server
        // ignores must not have made the code useless to the CLI that is listening on the loopback.
        HttpResponse<String> exchanged = exchange(queryParameter(redirect, "code"), verifier);
        assertThat(exchanged.statusCode()).as("redeeming: %s", exchanged.body()).isEqualTo(200);
    }

    /**
     * What the client is registered for is a ceiling, and the answer says which scopes were granted.
     *
     * <p><strong>The direction is the safe one deliberately:</strong> a request may narrow what it
     * asked for and may never widen it, so what the screen showed is at most what is granted, never
     * less. The credential is then spent, because a scope list in a response is a claim and a 403 is
     * the proof that it is the claim that matters.
     */
    @Test
    void theRequestedScopesAreIntersectedWithTheClientsAndNeverWidened() {
        begin();

        // One scope the client is registered for, beside one it is not — and `skills:admin` is not in
        // the server's vocabulary either, which is the point: the intersection is with the registered
        // row and nothing else was consulted.
        Pending pending = start(MACHINE, "skills:read skills:admin");
        HttpResponse<String> exchanged = exchange(pending.code(), pending.verifier());
        assertThat(exchanged.statusCode()).as("redeeming: %s", exchanged.body()).isEqualTo(200);
        assertThat(body(exchanged).get("scope").asText())
                .as("the granted set, which is not the requested set")
                .isEqualTo("skills:read");

        String token = body(exchanged).get("access_token").asText();
        HttpResponse<String> write = send(request("/api/v1/skills", token)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
        assertThat(write.statusCode())
                .as("a credential granted read alone writing: %s", write.body())
                .isEqualTo(403);
        assertThat(body(write).get("error").get("code").asText()).isEqualTo("insufficient_scope");
    }

    // ------------------------------------------------------------------------- ending a login

    /**
     * Ending a login takes effect on the credential's next request, and it is `token_revoked` rather
     * than `unauthenticated`.
     *
     * <p><strong>The two are different sentences for the person</strong> (prd 验收 #11): a credential
     * that matches nothing is a credential they no longer have, while one whose login was ended tells
     * them that something they did — on the console, or on the machine itself — took effect. Only the
     * second can be said, and only because the authorization's own row carries the mark.
     */
    @Test
    void endingALoginIsReflectedOnTheCredentialsNextRequest() {
        begin();
        Pending pending = start(MACHINE);
        String token = redeem(pending);
        assertThat(get(ME, token).statusCode()).isEqualTo(200);

        HttpResponse<String> ended =
                webPost(SIGN_INS + "/" + authorizationId() + "/revoke", "", csrfToken());
        assertThat(ended.statusCode()).as("ending the login: %s", ended.body()).isEqualTo(204);

        HttpResponse<String> refused = get(ME, token);
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(body(refused).get("error").get("code").asText())
                .as("a login somebody ended, not a credential that matches nothing")
                .isEqualTo("token_revoked");
    }

    /**
     * Somebody else's login and a login that never existed get one answer, and neither changes
     * anything.
     *
     * <p><strong>Telling them apart would confirm that a login with that id exists</strong> — and a
     * session-holding stranger can walk the space of ids, which is the oracle the single 404 closes
     * (§4.2's rule, as {@code ApiKeyIT} applies it to keys). The second half of the case is the owner's
     * side: a refused attempt must not have ended the login it named.
     */
    @Test
    void somebodyElsesLoginAndOneThatNeverExistedAreTheSameAnswer() {
        begin();
        String token = redeem(start(MACHINE));
        String mine = authorizationId();

        Browser intruder = registerAnotherAccount(randomUsername(), randomPhone());

        HttpResponse<String> notMine = intruder.post(uri(SIGN_INS + "/" + mine + "/revoke"), "",
                intruder.csrfToken());
        HttpResponse<String> noSuchThing =
                intruder.post(uri(SIGN_INS + "/" + NEVER_ISSUED + "/revoke"), "",
                        intruder.csrfToken());

        assertThat(notMine.statusCode()).isEqualTo(404);
        assertThat(noSuchThing.statusCode()).isEqualTo(404);
        assertThat(body(notMine).get("error").get("code").asText())
                .isEqualTo(body(noSuchThing).get("error").get("code").asText())
                .as("both are 'no login of yours with that id'")
                .isEqualTo("session_not_found");
        assertThat(get(ME, token).statusCode()).as("the owner's login must not have been touched")
                .isEqualTo(200);
    }

    /**
     * A repeated login on the same machine ends the one it replaces — Q7, and the server's half of it.
     *
     * <p><strong>The name is the only thing the server can recognise a machine by</strong>, which has a
     * cost: two machines given the same name end each other. The control at the end is the other half
     * of that sentence — a different name is a different machine and ends nothing — because without it
     * the rule being tested would be "any second login ends the first", which loses somebody's login
     * the moment they connect a second machine.
     *
     * <p>Where it happens is asserted too: the replacement is recorded as what it was rather than as a
     * logout, so the trail does not describe an ordinary sign-out for a machine that is still there.
     */
    @Test
    void aRepeatedLoginOnTheSameMachineEndsTheOneItReplaces() {
        begin();

        String first = redeem(start(MACHINE));
        assertThat(get(ME, first).statusCode()).isEqualTo(200);

        String second = redeem(start(MACHINE));
        assertThat(get(ME, second).statusCode()).isEqualTo(200);
        assertThat(body(get(ME, first)).get("error").get("code").asText())
                .as("the login this one replaced")
                .isEqualTo("token_revoked");

        assertThat(count("SELECT count(*) FROM oauth_authorization WHERE label = :label"
                + " AND revoked_at IS NULL", Map.of("label", MACHINE)))
                .as("one live login for that machine, which is what 'it does not accumulate' means")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'token_revoke'"
                + " AND detail LIKE '%cli_sign_in_replaced%'"))
                .as("recorded as a replacement rather than as somebody logging out")
                .isEqualTo(1);

        String elsewhere = redeem(start("the build box"));
        assertThat(get(ME, second).statusCode())
                .as("another machine's login must not end this one")
                .isEqualTo(200);
        assertThat(get(ME, elsewhere).statusCode()).isEqualTo(200);
    }

    /**
     * 「退出其他所有设备」 ends this account's CLI logins too — the sentence on the screen, made true.
     *
     * <p><strong>That screen counts the rows it is showing, and since v4 a CLI login is one of
     * them</strong> (Q6: the same two rulers, the same action), so 「退出后，其他 N 台设备都需要重新登录」
     * is a promise about rows of both kinds. The endpoint ended only the browser half, which left every
     * CLI row on that screen alive on a machine the person had just been told to sign in on again.
     *
     * <p><strong>Both directions are asserted, because either alone passes on a broken merge.</strong>
     * A button that ended only sessions leaves the CLI credential working after the sentence said it
     * would not; one that ended every authorization without excluding the caller would be a logout on
     * the screen that reports it, which is the failure {@code revokeOthers} exists to prevent on the
     * browser side. The stranger's login is the third direction: both modules act by user id, and a
     * sweep that reached past that key would end somebody else's machine.
     *
     * <p><strong>The trail is checked in the vocabulary each module already has</strong> — M1's
     * {@code session_revoke}, M2's {@code token_revoke_all} — because that is what "the login was
     * ended" has to be answerable from afterwards. No event is invented for this act, so none is
     * expected here.
     */
    @Test
    void revokeOthersEndsThisAccountsCliLoginsAndLeavesEveryoneElseAlone() {
        seedCliClient();
        String phone = randomPhone();
        String userId = registerAndSignIn(randomUsername(), phone);
        // A second browser of this account, so that "the others" is more than the CLI login and the
        // count in the answer has something to be about.
        Browser second = secondSignedInBrowser(phone, PASSWORD);

        String mine = redeem(start(MACHINE));
        assertThat(get(ME, mine).statusCode()).as("the login this case is about").isEqualTo(200);

        Browser other = registerAnotherAccount(randomUsername(), randomPhone());
        String theirs = redeem(startThrough(other, "their MacBook"));
        assertThat(get(ME, theirs).statusCode()).as("another account's login").isEqualTo(200);

        HttpResponse<String> response = webPost(SESSIONS + "/revoke-others", "", csrfToken());
        assertThat(response.statusCode()).as("ending the others: %s", response.body()).isEqualTo(200);
        assertThat(body(response).get("revoked").asInt())
                .as("the number is the sessions M1 ended: the CLI logins go through the other module,"
                        + " which answers nothing by design")
                .isEqualTo(1);

        // The CLI login is gone from both places it could be seen: refused on its next request, and no
        // longer one of the rows the sentence counted.
        HttpResponse<String> refused = get(ME, mine);
        assertThat(refused.statusCode()).as("a CLI login is one of the 'other devices'").isEqualTo(401);
        assertThat(body(refused).get("error").get("code").asText())
                .as("ended, not a credential that matches nothing").isEqualTo("token_revoked");
        assertThat(rowsOfKind(sessionsOf(), "cli")).as("the row the sentence was about").isEmpty();

        // The session that asked survives — that is what "other devices" means — and the account's
        // other browser does not.
        assertThat(webGet(SESSION).statusCode()).isEqualTo(200);
        assertThat(second.get(uri(SESSION)).statusCode()).as("the other browser device").isEqualTo(401);

        // A stranger's login, untouched by a write made on this one's session.
        assertThat(get(ME, theirs).statusCode()).as("another account's login must not have been reached")
                .isEqualTo(200);

        // Exactly one bulk revocation in the trail, and it names this account: the stranger's absence
        // from it is the same fact as the 200 above, said where the trail can be read.
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'token_revoke_all'"
                + " AND actor_user_id = :id", Map.of("id", userId)))
                .as("the CLI logins' ending is recorded, under the name M2 already uses for it")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'token_revoke_all'"))
                .as("and it is the only one, so no other account's logins were swept")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------- the console's 登录设备 list

    /**
     * T2 · the login appears on 「登录设备」 as a device, beside the browser session that approved it.
     *
     * <p><strong>The list is one list for two kinds of login, and this is what that means on the
     * wire.</strong> Not two arrays, not two screens, and no marker: both rows carry the same fields,
     * both are counted by the same number, and the one difference is {@code kind}, which is what the
     * screen posts to when the row is ended. Its second segment is the design's — {@code CLI · <the
     * name the person typed>} — and it is built here rather than by the module, because that is the
     * layer that turns a stored fact into what a screen shows.
     *
     * <p>Ending it goes through the route of its own plane, addressed by the id the row carries: a
     * CLI login is an authorization, not a session, and a single endpoint that guessed between them
     * would be a second interpreter of the same id.
     */
    @Test
    void theConsoleShowsALoginAsADeviceBesideTheBrowserSessionAndEndsItByItsOwnRoute() {
        String userId = begin();
        String token = redeem(start(MACHINE));

        JsonNode rows = sessionsOf();
        assertThat(rows).as("the browser session that approved it, and the CLI login").hasSize(2);

        JsonNode login = rowsOfKind(rows, "cli").get(0);
        assertThat(login.get("device").asText())
                .as("the kind of device, then the name the person typed — one segment, not a marker")
                .isEqualTo("CLI · " + MACHINE);
        assertThat(login.get("device_id").asText()).as("what the row is ended by")
                .isEqualTo(authorizationId());
        assertThat(login.get("address").asText())
                .as("where it was approved from — the console visitor's address, kept on the row (V16)")
                .isNotBlank();
        assertThat(login.get("logged_in_at").asText()).isNotBlank();
        assertThat(login.get("last_seen_at").asText())
                .as("a login approved a moment ago has been active once, and the row says when")
                .isNotBlank();
        assertThat(login.get("current").asBoolean())
                .as("the person reading this page is on a browser, not on that machine")
                .isFalse();

        // **The order is the merged list's own decision**, and it is by activity across both kinds:
        // neither module can decide it, because neither sees what the other contributed. Both
        // directions are pinned, since a merge that simply appended one list to the other would pass
        // whichever direction came out right by luck.
        //
        // The session is moved two hours back — inside the idle ruler, so it is still a valid session
        // and still the one making the request — while the login was approved a moment ago.
        moveSessionClock(userId, Duration.ofHours(2), Duration.ofHours(2));
        assertThat(sessionsOf().get(0).get("kind").asText())
                .as("the login approved a moment ago, against a session last used two hours ago")
                .isEqualTo("cli");

        // And the other way round: the column is sampled, so writing it is the same thing a request
        // would have done, just without waiting for a window to pass.
        jdbc.sql("UPDATE oauth_authorization SET last_used_at = '2020-01-01T00:00:00Z'").update();
        assertThat(sessionsOf().get(0).get("kind").asText())
                .as("the browser session reading this page, against a login last used in 2020")
                .isEqualTo("browser");

        HttpResponse<String> ended = webPost(
                SIGN_INS + "/" + login.get("device_id").asText() + "/revoke", "", csrfToken());
        assertThat(ended.statusCode()).as("ending the login: %s", ended.body()).isEqualTo(204);
        assertThat(sessionsOf()).as("the row is gone, and that is the result the screen shows")
                .hasSize(1);
        assertThat(get(ME, token).statusCode()).as("and the credential stops working now")
                .isEqualTo(401);
    }

    /**
     * A login whose credential has run out is not a device any more, and the list is what has to say
     * so.
     *
     * <p><strong>Nothing sweeps these rows.</strong> A browser session is deleted by the framework
     * when it expires; an authorization row is not deleted by anybody, so a list built from
     * "not revoked" alone would keep offering a device that cannot make a request — and the screen's
     * count (「N 台设备」) would then be a number about rows rather than about logins. The expiry is
     * the test, and it is one column rather than two: {@code expires_at} is
     * {@code min(last use + idle, issuance + absolute)}, so "later than now" already covers both
     * rulers.
     *
     * <p>While the credential was live the row was there — asserted first, so that this cannot pass
     * by the login never having been listed at all.
     */
    @Test
    void aLoginWhoseCredentialHasRunOutIsNoLongerADevice() {
        begin();
        redeem(start(MACHINE));
        assertThat(rowsOfKind(sessionsOf(), "cli")).as("a live login is a device").hasSize(1);

        // Pushed past its own expiry, which is also past the ceiling — the expiry is capped there, so
        // the two deaths cannot be told apart from this column (that is what the console's ending
        // action is for, and what the CLI's own refusal vocabulary distinguishes separately).
        assertThat(jdbc.sql("UPDATE access_token SET expires_at = '2000-01-01T00:00:00Z'").update())
                .as("the credential this case is about")
                .isEqualTo(1);

        assertThat(rowsOfKind(sessionsOf(), "cli"))
                .as("a login nobody can make a request with must not be offered as one")
                .isEmpty();
    }

    // ------------------------------------------------------------------- the session plane's edges

    /**
     * Without a session the mint is impossible, and nothing is recorded on the way to the refusal.
     *
     * <p><strong>This is the line that stops the endpoint being open to the world.</strong> CSRF alone
     * would not: it refuses a cross-site write, but it never asks who is asking — so without the
     * authorization rule anybody who could compose the request could mint a login credential. The
     * client below has read something first, so it holds a CSRF token and the refusal cannot be the
     * CSRF filter's.
     */
    @Test
    void startingWithoutASessionIsRefusedAndNothingIsRecorded() {
        seedCliClient();
        Browser stranger = new Browser();
        stranger.get(uri(SESSION));

        HttpResponse<String> refused = stranger.post(uri(SIGN_INS),
                loginBody(MACHINE, REGISTERED_SCOPES, codeVerifier()), stranger.csrfToken());

        assertThat(refused.statusCode()).isEqualTo(401);
        // The browser plane's own entry point, not the bearer challenge: the two planes do not lend
        // each other their shapes, and a `WWW-Authenticate: Bearer` would send a browser client after
        // a token it has no use for.
        assertThat(wwwAuthenticate(refused)).isNull();
        assertThat(count("SELECT count(*) FROM oauth_authorization")).isZero();
        assertThat(count("SELECT count(*) FROM cli_sign_in_code")).isZero();
    }

    /** And without CSRF it is refused before routing reaches the controller. */
    @Test
    void startingWithoutACsrfHeaderIsRefused() {
        begin();

        HttpResponse<String> refused =
                webPost(SIGN_INS, loginBody(MACHINE, REGISTERED_SCOPES, codeVerifier()), null);

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM oauth_authorization"))
                .as("nothing may have been started").isZero();
    }

    /**
     * The exchange serves a client that has no session at all, and the mint does not — which is the
     * whole of why the two are on different planes.
     *
     * <p><strong>The client is the suite's bare one</strong>: {@code AbstractIT}'s static
     * {@code HttpClient}, which has no cookie jar and is what every API-plane test in this suite uses.
     * It is asked both addresses here, and the pair is the assertion — one of these routes is a login,
     * and the other is a delivery to a process that has never had one.
     */
    @Test
    void theExchangeServesAClientWithNoSessionAndTheMintDoesNot() {
        begin();
        Pending pending = start(MACHINE);

        HttpResponse<String> exchanged = exchange(pending.code(), pending.verifier());
        assertThat(exchanged.statusCode()).as("a client holding nothing: %s", exchanged.body())
                .isEqualTo(200);
        assertThat(get(ME, body(exchanged).get("access_token").asText()).statusCode())
                .as("and the credential it just received is usable")
                .isEqualTo(200);

        // The sibling route, from the same client: a login begins with a person, and this one is
        // refused. **403 rather than 401, and that is the order the plane works in** — CSRF is judged
        // before the session, and a client with no cookie jar cannot hold either the token or the
        // session. Which refusal a session-less caller meets is why
        // `startingWithoutASessionIsRefusedAndNothingIsRecorded` presents a token: there the question
        // is the authorization rule, and it has to be asked past this one.
        long before = count("SELECT count(*) FROM oauth_authorization");
        HttpResponse<String> mint = send(request(SIGN_INS, null)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        loginBody(MACHINE, REGISTERED_SCOPES, codeVerifier())))
                .build());
        assertThat(mint.statusCode()).as("the mint from a client holding nothing: %s", mint.body())
                .isEqualTo(403);
        assertThat(count("SELECT count(*) FROM oauth_authorization"))
                .as("the only login in the table is the one this case started in the browser")
                .isEqualTo(before);
    }

    /*
     * The arrangements and the small readers below. Each asserts what it needed to happen, because an
     * arrangement that silently did not take effect would surface as a puzzling failure in whatever
     * the case was actually about.
     */

    /**
     * The client row and an account — what every case here starts from.
     *
     * <p>The two halves arrive in this order because the row is what the login reads its scopes and its
     * redirect from, and a login started without it fails as a deployment error rather than as a
     * request error.
     *
     * @return the new account's user id
     */
    private String begin() {
        seedCliClient();
        return registerAndSignIn(randomUsername(), randomPhone());
    }

    /** A login that has been started and not yet redeemed: the code, and the verifier that redeems it. */
    private record Pending(String verifier, String code) {
    }

    private Pending start(String name) {
        return start(name, REGISTERED_SCOPES);
    }

    /** Starts a login and insists it worked, which is the arrangement every later step assumes. */
    private Pending start(String name, String scope) {
        String verifier = codeVerifier();
        HttpResponse<String> response = startRequest(name, scope, verifier);
        assertThat(response.statusCode()).as("starting a login: %s", response.body()).isEqualTo(201);
        return new Pending(verifier, codeOf(response));
    }

    /** The start request, with nothing asserted about what it answers — several cases are about that. */
    private HttpResponse<String> startRequest(String name, String scope, String verifier) {
        return webPost(SIGN_INS, loginBody(name, scope, verifier), csrfToken());
    }

    /**
     * The same, started by a browser other than the primary one.
     *
     * <p>A login belongs to the session that approved it, so the case that needs a second account's
     * login has to make it through that account's own jar — the primary {@link #start} would begin it
     * for whoever this class is signed in as.
     */
    private Pending startThrough(Browser who, String name) {
        String verifier = codeVerifier();
        HttpResponse<String> response = who.post(uri(SIGN_INS),
                loginBody(name, REGISTERED_SCOPES, verifier), who.csrfToken());
        assertThat(response.statusCode()).as("starting a login: %s", response.body()).isEqualTo(201);
        return new Pending(verifier, codeOf(response));
    }

    /**
     * The body the screen posts: the name it collected, plus the four parameters it read off its own
     * address and sends back unread by the server.
     *
     * <p>Snake case written out, because the tests send raw maps and no naming strategy applies to
     * them — the server's own strategy is what turns {@code client_id} into the field it reads.
     *
     * @param name null to send no name at all, which is a different refusal from a blank one
     */
    private static String loginBody(String name, String scope, String verifier) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("client_id", CLIENT_ID);
        body.put("scope", scope);
        body.put("state", STATE);
        body.put("code_challenge", challenge(verifier));
        return JSON.writeValueAsString(body);
    }

    /**
     * Redeems a code exactly as the CLI does: a bare client, no cookie, the code and the verifier in a
     * JSON body.
     *
     * <p>Not through {@link #browser}: a request carrying the session cookie would answer a different
     * question, and this route's whole design is that it does not need one.
     */
    private HttpResponse<String> exchange(String code, String verifier) {
        return send(request(EXCHANGE, null)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("code", code, "code_verifier", verifier))))
                .build());
    }

    /** Redeems a fresh login and hands back the credential, which only this answer ever carries. */
    private String redeem(Pending pending) {
        HttpResponse<String> response = exchange(pending.code(), pending.verifier());
        assertThat(response.statusCode())
                .as("redeeming a fresh code: %s", response.body()).isEqualTo(200);
        return body(response).get("access_token").asText();
    }

    /** The code out of the answer the start gave, asserted before anything is read from it. */
    private String codeOf(HttpResponse<String> started) {
        String code = queryParameter(redirectOf(started), "code");
        assertThat(code).as("a code in %s", started.body()).isNotBlank();
        return code;
    }

    /**
     * The address the start hands the browser.
     *
     * <p>Asserted to be the registered address <em>plus a query</em> rather than merely the registered
     * address: a delivery that arrives at the loopback address without a code on it is not a delivery,
     * and {@code startsWith(base)} alone would let one through.
     */
    private String redirectOf(HttpResponse<String> started) {
        String redirect = body(started).get("redirect_to").asText();
        assertThat(redirect).as("the start's answer: %s", started.body())
                .startsWith(REDIRECT_URI + "?");
        return redirect;
    }

    /** The parameter names on an address — read to assert what a URL does <em>not</em> carry. */
    private static List<String> parameterNamesOf(String address) {
        return Arrays.stream(URI.create(address).getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2)[0])
                .toList();
    }

    /**
     * The id of the login this test started, read from the code row that carries it.
     *
     * <p>Read from the row rather than from the console's list, so that the two can be compared: the
     * listing is asserted to address the login by <em>this</em> value, and a list that invented its
     * own handle would fail instead of agreeing with itself.
     */
    private String authorizationId() {
        return jdbc.sql("SELECT authorization_id FROM cli_sign_in_code").query(String.class).single();
    }

    /** The console's list, read through the jar that is signed in. */
    private JsonNode sessionsOf() {
        HttpResponse<String> response = webGet(SESSIONS);
        assertThat(response.statusCode()).as("listing devices: %s", response.body()).isEqualTo(200);
        return body(response).get("sessions");
    }

    /** The rows of one kind. Two kinds share this list, so a case about one has to say which. */
    private static List<JsonNode> rowsOfKind(JsonNode rows, String kind) {
        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode row : rows) {
            if (kind.equals(row.get("kind").asText())) {
                matching.add(row);
            }
        }
        return matching;
    }

    /** Moves a code past its own deadline. The clock is not under test; the row is. */
    private void expire(String code) {
        int updated = jdbc.sql("UPDATE cli_sign_in_code SET expires_at = '2000-01-01T00:00:00Z'"
                        + " WHERE code_hash = :hash")
                .param("hash", Sha256Hex.of(code.getBytes(StandardCharsets.UTF_8)))
                .update();
        assertThat(updated).as("expiring the code this test just issued").isEqualTo(1);
    }

    /**
     * How many stored values in the tables this version writes contain {@code secret}.
     *
     * <p><strong>One statement with the table names written out, because the exhaustive version is a
     * scan of {@code information_schema} with SQL built from strings.</strong> What it covers is what a
     * leak could come from — the token tables, the one-time code table, the keys beside them and the
     * audit trail — every place a sign-in writes. It is not the whole schema, and it does not claim to
     * be. {@code strpos} rather than {@code LIKE} so that the search is for the value and not for a
     * pattern.
     */
    private long rowsContaining(String secret) {
        return jdbc.sql("SELECT count(*) FROM ("
                        + " SELECT token_hash AS stored FROM access_token"
                        + " UNION ALL SELECT token_hash FROM refresh_token"
                        + " UNION ALL SELECT code_hash FROM auth_code"
                        + " UNION ALL SELECT code_hash FROM cli_sign_in_code"
                        + " UNION ALL SELECT token_hash FROM api_key"
                        + " UNION ALL SELECT detail FROM audit_event"
                        + " UNION ALL SELECT target_id FROM audit_event"
                        + " UNION ALL SELECT label FROM oauth_authorization"
                        + ") AS written WHERE strpos(stored, :secret) > 0")
                .param("secret", secret)
                .query(Long.class)
                .single();
    }

    /**
     * Registers a second account in a browser of its own, so that one case can act as two people.
     *
     * <p>The code is asked for over the primary jar because a verification code belongs to a phone
     * number and not to a client; the registration itself goes through the new jar, which is what makes
     * its session another account's.
     */
    private Browser registerAnotherAccount(String username, String phone) {
        Browser other = new Browser();
        // A fresh client has no CSRF token until it has read something, exactly as a browser does.
        other.get(uri(SESSION));
        HttpResponse<String> created = other.post(uri(REGISTER), json(Map.of(
                "phone", phone, "code", requestCode(phone),
                "password", PASSWORD, "username", username)), other.csrfToken());
        assertThat(created.statusCode())
                .as("registering %s: %s", username, created.body())
                .isEqualTo(201);
        return other;
    }
}
