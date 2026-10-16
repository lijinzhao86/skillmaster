package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Browser;
import org.springframework.test.context.jdbc.Sql;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The whole of v2's credential chain, over real HTTP: sign in, issue a key from the console, use it
 * on the API plane, then revoke, expire, suspend and renew it.
 *
 * <p><strong>It goes through both planes in one test because the credential does.</strong> What v2
 * changes is how a machine comes to hold a credential, and every claim about that is a claim about
 * two planes at once: a key issued by a session and spent as a bearer token. A test of either half
 * alone would pass with the other half disconnected.
 *
 * <p>Several tests write a timestamp straight into the row — {@code expires_at} into the past,
 * {@code last_used_at} beyond the staleness window, {@code last_used_from} to another address. That
 * is deliberate and is the same technique {@code IssuedTokenValidatorIT} uses: the alternative is a
 * clock, and the behaviour under test is what the request path does with a row, not how the row came
 * to look like that.
 */
@Sql("/sql/truncate-business-tables.sql")
class ApiKeyIT extends AbstractAccountIT {

    private static final String KEYS = "/web/keys";
    private static final String ME = "/api/v1/me";

    @Test
    void aKeyIssuedFromTheConsoleWorksOnTheApiPlane() {
        String userId = registerAndSignIn("keyuser", randomPhone());

        JsonNode issued = issueKey(keys -> {
            keys.put("name", "laptop");
            keys.put("note", "the one on my desk");
            keys.put("scopes", List.of("skills:read"));
        });
        String token = issued.get("token").asText();

        // The shape the CLI checks before it spends a request, asserted here because the server is
        // where the shape comes from: `skm_` and 43 characters of base64url.
        assertThat(token).startsWith("skm_").hasSize(47);
        assertThat(issued.get("key").get("name").asText()).isEqualTo("laptop");
        assertThat(issued.get("key").get("expires_at").asText())
                .as("a key issued with no stated date takes the deployment's default")
                .isNotBlank();

        HttpResponse<String> me = get(ME, token);
        assertThat(me.statusCode()).as("using the new key: %s", me.body()).isEqualTo(200);
        JsonNode body = body(me);
        assertThat(body.get("user").get("username").asText()).isEqualTo("keyuser");
        assertThat(body.get("user").get("namespace").asText()).isEqualTo("keyuser");
        // The key half: which key this is, read out of the row by the id the subject carries.
        assertThat(body.get("key").get("name").asText()).isEqualTo("laptop");
        assertThat(body.get("key").get("scope").asText()).isEqualTo("skills:read");
        assertThat(body.get("key").get("id").asText()).isEqualTo(issued.get("key").get("id").asText());

        // The database holds a hash and not the key: a leak is not a credential leak (ADR 0007).
        String stored = jdbc.sql("SELECT token_hash FROM api_key WHERE user_id = :id")
                .param("id", userId).query(String.class).single();
        assertThat(stored).hasSize(64).isNotEqualTo(token);
        assertThat(token).doesNotContain(stored);
    }

    @Test
    void aWriteImpliesAReadAtIssuance() {
        registerAndSignIn("writer", randomPhone());

        JsonNode issued = issueKey(keys -> {
            keys.put("name", "ci");
            keys.put("scopes", List.of("skills:write"));
        });

        // Folded in when the row was written, not special-cased in the auth chain: the row is the
        // truth, so a `skill list` with this key is not a second decision about the same rule.
        assertThat(issued.get("key").get("scope").asText()).isEqualTo("skills:read skills:write");
        assertThat(get(ME, issued.get("token").asText()).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/skills?q=nothing", issued.get("token").asText()).statusCode())
                .as("a key granted write must be able to read").isEqualTo(200);
    }

    @Test
    void theListingNeverCarriesTheSecret() {
        registerAndSignIn("lister", randomPhone());
        String token = issueKey(keys -> {
            keys.put("name", "one");
            keys.put("scopes", List.of("skills:read"));
        }).get("token").asText();

        HttpResponse<String> listing = webGet(KEYS);

        assertThat(listing.statusCode()).isEqualTo(200);
        assertThat(listing.body()).doesNotContain(token);
        JsonNode keys = body(listing).get("keys");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).has("token")).as("a listing must not carry a secret field").isFalse();

        // The policy rides along, because the create form cannot guess it: the design preselects 90
        // days, but a deployment that turned expiry off issues keys that never expire — and a form
        // that insisted on a date would send one, which wins over the default.
        JsonNode policy = body(listing).get("policy");
        assertThat(policy.get("expires").asBoolean()).isTrue();
        // One number, two jobs: the form preselects it and the server refuses anything beyond it
        // (ADR 0042), so the console never offers a date this deployment would reject.
        assertThat(policy.get("lifetime_days").asLong()).isEqualTo(90);
    }

    @Test
    void aReadOnlyKeyIsRefusedAWrite() {
        registerAndSignIn("reader", randomPhone());
        String token = issueKey(keys -> {
            keys.put("name", "read-only");
            keys.put("scopes", List.of("skills:read"));
        }).get("token").asText();

        // A write verb, so the scope rule applies before anything is routed or resolved. The address
        // is one that does not exist, which is the point: this refusal is about the credential.
        HttpResponse<String> refused = send(request("/api/v1/skills/someone/nothing", token)
                .DELETE().build());

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("insufficient_scope");
        assertThat(wwwAuthenticate(refused)).contains("skills:write");
    }

    @Test
    void revokingStopsTheKeyImmediatelyAndIsIdempotent() {
        registerAndSignIn("revoker", randomPhone());
        JsonNode issued = issueKey(keys -> {
            keys.put("name", "doomed");
            keys.put("scopes", List.of("skills:read"));
        });
        String id = issued.get("key").get("id").asText();
        String token = issued.get("token").asText();
        assertThat(get(ME, token).statusCode()).isEqualTo(200);

        assertThat(revoke(id).statusCode()).isEqualTo(204);

        HttpResponse<String> refused = get(ME, token);
        assertThat(refused.statusCode()).as("a revoked key was accepted").isEqualTo(401);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("token_revoked");
        // The challenge is unchanged: the reason lives in the envelope, which is the one authority
        // for it, and RFC 6750's header keeps its one spelling.
        assertThat(wwwAuthenticate(refused)).contains("error=\"invalid_token\"");

        // The listing still shows it, marked — "I revoked it" and "it was never there" are different
        // answers and the second one is alarming.
        JsonNode listed = body(webGet(KEYS)).get("keys").get(0);
        assertThat(listed.get("revoked_at").asText()).isNotBlank();

        // A second click is not an error and must not move the timestamp.
        String firstRevocation = listed.get("revoked_at").asText();
        assertThat(revoke(id).statusCode()).isEqualTo(204);
        assertThat(body(webGet(KEYS)).get("keys").get(0).get("revoked_at").asText())
                .isEqualTo(firstRevocation);
    }

    @Test
    void anExpiredKeySaysExpiredAndARevokedOneSaysRevoked() {
        registerAndSignIn("expiring", randomPhone());
        JsonNode issued = issueKey(keys -> {
            keys.put("name", "old");
            keys.put("scopes", List.of("skills:read"));
        });
        String expired = issued.get("token").asText();

        expire(issued.get("key").get("id").asText());

        // The two answers a person has to act on differently: renew the expired one, replace the
        // revoked one. Same status, different code — ADR 0039 §决定 3 and acceptance #1.
        HttpResponse<String> refused = get(ME, expired);
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("token_expired");
    }

    @Test
    void theReasonFollowsThePriorityTheDesignFixed() {
        String userId = registerAndSignIn("mixedstates", randomPhone());
        JsonNode issued = issueKey(keys -> {
            keys.put("name", "both");
            keys.put("scopes", List.of("skills:read"));
        });
        String both = issued.get("token").asText();
        String id = issued.get("key").get("id").asText();

        // Revoked *and* expired: the deliberate act is reported, because it is the one somebody did
        // and the one that sends the person to "issue a new one" rather than to "renew".
        jdbc.sql("UPDATE api_key SET expires_at = '2000-01-01T00:00:00Z',"
                + " revoked_at = '2026-01-01T00:00:00Z' WHERE id = :id").param("id", id).update();
        assertThat(body(get(ME, both)).get("error").get("code").asText()).isEqualTo("token_revoked");

        // Suspended *and* expired: the account is reported, because renewing the key would change
        // nothing.
        jdbc.sql("UPDATE api_key SET revoked_at = NULL, expires_at = '2000-01-01T00:00:00Z'"
                + " WHERE id = :id").param("id", id).update();
        suspend(userId);
        HttpResponse<String> refused = get(ME, both);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("account_suspended");

        // **Suspended *and* revoked — the top of the order, and the pair that was missing.** The two
        // cases above each leave the pair being compared with only one condition true, so swapping the
        // first two branches of the validator kept every test green while contradicting the order the
        // design fixed (撤销 → 停用 → 过期): a revoked key on a suspended account must still be
        // reported as revoked, because "issue a new one" is the answer that fits a revoked credential
        // and suspension is the account's business.
        jdbc.sql("UPDATE api_key SET revoked_at = '2026-01-01T00:00:00Z' WHERE id = :id")
                .param("id", id).update();
        assertThat(body(get(ME, both)).get("error").get("code").asText())
                .as("revoked outranks suspended").isEqualTo("token_revoked");
    }

    @Test
    void aSuspendedAccountIsNotAboutTheKey() {
        String userId = registerAndSignIn("suspended", randomPhone());
        String token = issueKey(keys -> {
            keys.put("name", "fine");
            keys.put("scopes", List.of("skills:read"));
        }).get("token").asText();
        assertThat(get(ME, token).statusCode()).isEqualTo(200);

        suspend(userId);

        // Checked on every request rather than at issuance, which is what makes suspension take
        // effect now — a credential that has already been handed out cannot be recalled (ADR 0038
        // §决定 4).
        HttpResponse<String> refused = get(ME, token);
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("account_suspended");
    }

    @Test
    void aKeyShapedStringThatMatchesNothingIsOnlyUnauthenticated() {
        registerAndSignIn("guessy", randomPhone());

        HttpResponse<String> refused = get(ME, "skm_" + "0".repeat(43));

        // The boundary TokenValidation draws: a guess learns nothing, because learning that a key
        // exists requires already holding it. Contrast the tests above, where the reason *is* given —
        // to somebody who presented a credential this service has a row for.
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("unauthenticated");
    }

    @Test
    void renewalReplacesTheKeyAndRekeysTheDate() {
        registerAndSignIn("renewer", randomPhone());
        JsonNode first = issueKey(keys -> {
            keys.put("name", "long-lived");
            keys.put("note", "still the build server");
            keys.put("scopes", List.of("skills:write"));
        });
        String id = first.get("key").get("id").asText();
        String oldToken = first.get("token").asText();

        // Expired first, because a renewal is what somebody does when a key is about to stop working
        // — and a renewed key that inherited the old date would be a key that is already dead.
        expire(id);
        assertThat(body(get(ME, oldToken)).get("error").get("code").asText())
                .isEqualTo("token_expired");

        HttpResponse<String> renewed = webPost(KEYS + "/" + id + "/rotate", "", csrfToken());
        assertThat(renewed.statusCode()).as("renewing: %s", renewed.body()).isEqualTo(200);
        JsonNode replacement = body(renewed);
        String newToken = replacement.get("token").asText();
        String newId = replacement.get("key").get("id").asText();

        assertThat(newId).isNotEqualTo(id);
        assertThat(replacement.get("key").get("name").asText()).isEqualTo("long-lived");
        assertThat(replacement.get("key").get("note").asText()).isEqualTo("still the build server");
        assertThat(replacement.get("key").get("scope").asText()).isEqualTo("skills:read skills:write");
        assertThat(Instant.parse(replacement.get("key").get("expires_at").asText()))
                .as("the new key takes a newly computed date rather than the old one")
                .isAfter(Instant.now().plus(Duration.ofDays(80)));

        assertThat(get(ME, newToken).statusCode()).isEqualTo(200);
        assertThat(body(get(ME, oldToken)).get("error").get("code").asText())
                .as("the replaced key must be dead the moment the new one exists")
                .isEqualTo("token_revoked");
    }

    @Test
    void aRevokedKeyCannotBeRenewed() {
        registerAndSignIn("nolazarus", randomPhone());
        String id = issueKey(keys -> {
            keys.put("name", "done");
            keys.put("scopes", List.of("skills:read"));
        }).get("key").get("id").asText();
        assertThat(revoke(id).statusCode()).isEqualTo(204);

        HttpResponse<String> refused = webPost(KEYS + "/" + id + "/rotate", "", csrfToken());

        // There is nothing to continue: revocation is a decision that this credential must not work,
        // and renewing it would undo that decision without anybody saying so.
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("invalid_request");
        assertThat(field(refused)).isEqualTo("id");
        assertThat(issue(refused)).isEqualTo("revoked");
    }

    @Test
    void twoRenewalsAtOnceLeaveExactlyOneLiveKey() throws Exception {
        String userId = registerAndSignIn("doubleclick", randomPhone());
        String id = issueKey(keys -> keys.put("name", "double")).get("key").get("id").asText();
        String csrf = csrfToken();

        // **The console makes this easy to reach**: confirming a renewal is one POST, and a double
        // click is two of them arriving together. Both requests read a live row before either has
        // stopped it, so both reach the statement that stops it — and what must not happen is that
        // both carry on to issue a successor. That would leave the account with two live keys, only
        // one of which anybody ever saw, which is exactly the overlap the renewal contract forbids
        // (technical-design §接口: 先撤旧的、再建新的，两者不重叠).
        CyclicBarrier together = new CyclicBarrier(2);
        List<Callable<HttpResponse<String>>> both = List.of(
                () -> {
                    together.await();
                    return webPost(KEYS + "/" + id + "/rotate", "", csrf);
                },
                () -> {
                    together.await();
                    return webPost(KEYS + "/" + id + "/rotate", "", csrf);
                });

        List<HttpResponse<String>> answers;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            answers = new ArrayList<>();
            for (Future<HttpResponse<String>> answer : pool.invokeAll(both)) {
                answers.add(answer.get());
            }
        }

        // One renewal happened and the other was told the truth about what it was renewing: the key
        // it named had just been revoked. Which of the two wins is the database's business; that
        // exactly one wins is the contract.
        assertThat(answers.stream().filter(answer -> answer.statusCode() == 200).count())
                .as("renewals that succeeded: %s", answers.stream().map(HttpResponse::body).toList())
                .isEqualTo(1);
        HttpResponse<String> refused = answers.stream()
                .filter(answer -> answer.statusCode() != 200).findFirst().orElseThrow();
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("invalid_request");
        assertThat(field(refused)).isEqualTo("id");
        assertThat(issue(refused)).isEqualTo("revoked");

        assertThat(jdbc.sql("SELECT count(*) FROM api_key WHERE user_id = :id AND revoked_at IS NULL")
                .param("id", userId).query(Long.class).single())
                .as("live keys after two renewals of one key")
                .isEqualTo(1);
    }

    @Test
    void aDateWithinTheLifetimeIsIssuedAndBeyondItIsNot() {
        registerAndSignIn("ceiling", randomPhone());

        String afterSixty = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(90, ChronoUnit.DAYS)
                .toString();
        // A date this deployment issues, and one beyond it. `latest_day` is the day in between.
        assertThat(webPost(KEYS, json(issueBody(keys -> keys.put("expires_at", afterSixty))),
                csrfToken()).statusCode()).as("90 days from now").isEqualTo(201);
        String beyond = Instant.now().plus(120, ChronoUnit.DAYS).toString();
        assertThat(issueKeyRefused(keys -> keys.put("expires_at", beyond), "invalid_expiry"))
                .isEqualTo("beyond_maximum");
    }

    @Test
    void anExpiryThatWouldBeStoredInThePastIsRefused() {
        registerAndSignIn("subsecond", randomPhone());

        // **The last millisecond of this second.** Two readings of the same date disagree about it,
        // and both are honest: as it arrives it is in the future (the round trip to here is faster
        // than 999ms, and the field is whole milliseconds), while the timestamp that gets *stored* is
        // whole seconds — the start of a second that began before the request was made. A key issued
        // with this date is refused by its very first request with `token_expired`, and nothing in the
        // 201 said so.
        String doomed = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1).minusMillis(1)
                .toString();
        assertThat(issueKeyRefused(keys -> keys.put("expires_at", doomed), "invalid_expiry"))
                .isEqualTo("not_in_the_future");

        // And the opposite control, because the rule above must not have become "refuse everything
        // with a fraction": a whole second in the future is stored, and the key works.
        JsonNode issued = issueKey(keys -> keys.put("expires_at",
                Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60).toString()));
        HttpResponse<String> me = get(ME, issued.get("token").asText());
        assertThat(me.statusCode()).as("a key issued for a minute from now: %s", me.body())
                .isEqualTo(200);
    }

    @Test
    void anotherAccountsKeyIsNotThere() {
        registerAndSignIn("owneraccount", randomPhone());
        String id = issueKey(keys -> {
            keys.put("name", "private");
            keys.put("scopes", List.of("skills:read"));
        }).get("key").get("id").asText();

        Browser intruder = secondSignedInBrowser(randomPhoneOfAnotherAccount(), PASSWORD);

        // 404 and not 403, and the same answer as an id that names nothing at all: a distinct answer
        // would confirm that a key with that id exists (§4.2's rule, applied to credentials).
        HttpResponse<String> revoke = intruder.post(uri(KEYS + "/" + id + "/revoke"), "",
                intruder.csrfToken());
        assertThat(revoke.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(revoke.body()).get("error").get("code").asText())
                .isEqualTo("key_not_found");
        assertThat(ownerKeyIsStillLive(id))
                .as("the owner's key must not have been touched").isTrue();

        // **And the other half of the same rule**: a well-formed id that names nothing at all must
        // answer exactly as somebody else's does. Without this the two could drift apart — "not yours"
        // answering 403 and "no such key" answering 404 — and a session-holding stranger could walk
        // the space of ULIDs to learn which ones are real keys, which is the oracle the answer above
        // exists to close.
        String unused = "01M4ZS63TDCQEB3VVVVBAK1FRX";
        HttpResponse<String> absent = intruder.post(uri(KEYS + "/" + unused + "/revoke"), "",
                intruder.csrfToken());
        assertThat(absent.statusCode()).as("no such id: %s", absent.body()).isEqualTo(404);
        assertThat(JSON.readTree(absent.body()).get("error").get("code").asText())
                .as("the same answer as somebody else's key")
                .isEqualTo("key_not_found");
    }

    @Test
    void badIssuanceRequestsAreRefusedWithTheCodeForWhatIsWrong() {
        registerAndSignIn("sloppy", randomPhone());

        assertThat(issueKeyRefused(keys -> keys.put("name", "  "), "invalid_request")).isEqualTo("blank");
        // **A null among the scopes is a 400, not a 500.** The vocabulary check was
        // `Scopes.ALL.containsAll(requested)`, and an immutable set refuses a null probe by design —
        // so a hand-built body answered `internal_error` and the log line named our own bug rather than
        // the caller's. Reachable only by hand (the console sends one of two literals), which is why it
        // is a wrong answer rather than a broken path.
        assertThat(issueKeyRefused(keys -> {
            keys.put("name", "x");
            keys.put("scopes", java.util.Arrays.asList("skills:read", null));
        }, "invalid_scope")).isEqualTo("unknown");
        assertThat(issueKeyRefused(keys -> {
            keys.put("name", "x");
            keys.put("scopes", List.of("skills:admin"));
        }, "invalid_scope")).isEqualTo("unknown");
        // The field a client is told to fix is the one its request carries — `scopes`, as in the body
        // — and not the concept's singular. It said `scope`, so the console's own form looked for a
        // field it does not have and this refusal's sentence never reached the screen.
        assertThat(field(webPost(KEYS, json(issueBody(keys -> {
            keys.put("name", "x");
            keys.put("scopes", List.of("skills:admin"));
        })), csrfToken()))).isEqualTo("scopes");
        assertThat(issueKeyRefused(keys -> {
            keys.put("name", "x");
            keys.put("scopes", List.of());
        }, "invalid_scope")).isEqualTo("empty");
        // A date this deployment does not issue: the lifetime is a ceiling, not a suggestion (ADR
        // 0042). Refused rather than clamped, because clamping would hand back a key that is not the
        // one that was asked for, with nothing in the response saying so.
        assertThat(issueKeyRefused(keys -> {
            keys.put("name", "x");
            keys.put("expires_at", "9999-12-31T00:00:00Z");
        }, "invalid_expiry")).isEqualTo("beyond_maximum");
        // **And the date the console actually sends must be issuable**, which is the boundary this
        // rule has to be built around rather than a number that merely looks like it: the form sends
        // the *end of a day* — the day `lifetime` days from today, 23:59:59 in the zone it writes
        // dates in (technical design §分期 P1 ③) — while a ceiling of "now plus the duration" is an
        // instant in the middle of a day. Between those two readings lie the seconds already gone by
        // today, and a ceiling that ignores them refuses the default form on every submission.
        // **The day comes from the deployment, not from this test's clock.** The console reads
        // `latest_day` out of the listing for exactly this reason (ADR 0042 §决定 5): a client that
        // worked the bound out locally would refuse its own preselected date for however far its clock
        // is ahead of the server's around a day boundary. Deriving it here the same way is what ties
        // the published field to the arithmetic behind it.
        String lastDay = body(webGet(KEYS)).get("policy").get("latest_day").asText();
        assertThat(lastDay).matches("\\d{4}-\\d{2}-\\d{2}");

        String consoleDefault = LocalDate.parse(lastDay)
                .atTime(23, 59, 59)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toString();
        JsonNode atTheCeiling = issueKey(keys -> keys.put("name", "exactly"));
        assertThat(atTheCeiling.get("key").get("expires_at").asText()).isNotBlank();
        assertThat(webPost(KEYS, json(issueBody(keys -> {
            keys.put("name", "console-default");
            keys.put("expires_at", consoleDefault);
        })), csrfToken()).statusCode())
                .as("the date the console's form preselects, taken from latest_day")
                .isEqualTo(201);

        // One day further is not: the ceiling is a day boundary, and the next day's end is beyond it.
        String oneDayTooFar = LocalDate.parse(lastDay)
                .plusDays(1)
                .atTime(23, 59, 59)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toString();
        assertThat(issueKeyRefused(keys -> {
            keys.put("name", "x");
            keys.put("expires_at", oneDayTooFar);
        }, "invalid_expiry")).isEqualTo("beyond_maximum");
        assertThat(issueKeyRefused(keys -> {
            keys.put("name", "x");
            keys.put("scopes", List.of("skills:read"));
            keys.put("expires_at", "2000-01-01T00:00:00Z");
        }, "invalid_expiry")).isEqualTo("not_in_the_future");
    }

    @Test
    void issuingWithoutACsrfHeaderIsRefused() {
        registerAndSignIn("csrfless", randomPhone());
        String body = json(issueBody(keys -> {
            keys.put("name", "sneaky");
            keys.put("scopes", List.of("skills:read"));
        }));

        HttpResponse<String> refused = webPost(KEYS, body, null);

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM api_key")).as("nothing may have been issued").isZero();
    }

    @Test
    void issuingWithoutASessionIsAPlain401OnThisPlane() {
        String body = json(issueBody(keys -> {
            keys.put("name", "anonymous");
            keys.put("scopes", List.of("skills:read"));
        }));
        // A client with no session still has to satisfy CSRF before routing — that is the first
        // refusal, and it is why the request below carries a token. The 401 that follows is the
        // answer this test is about.
        Browser stranger = new Browser();
        stranger.get(uri(SESSION));

        HttpResponse<String> refused = stranger.post(uri(KEYS), body, stranger.csrfToken());

        assertThat(refused.statusCode()).isEqualTo(401);
        // The browser plane's entry point, not the bearer challenge: the two planes do not lend each
        // other their shapes (§4.1), and a `WWW-Authenticate: Bearer` here would tell a browser client
        // to go and find a token it has no use for.
        assertThat(wwwAuthenticate(refused)).isNull();
        assertThat(count("SELECT count(*) FROM api_key")).isZero();
    }

    @Test
    void useIsRecordedAndANewAddressIsNoticed() {
        String userId = registerAndSignIn("watched", randomPhone());
        String token = issueKey(keys -> {
            keys.put("name", "watched");
            keys.put("scopes", List.of("skills:read"));
        }).get("token").asText();

        assertThat(get(ME, token).statusCode()).isEqualTo(200);
        assertThat(lastUsedAt(userId)).as("using a key records that it was used").isNotBlank();

        // The stamp is throttled, so the second use inside the window must not rewrite it — and the
        // address signal is sampled at the same rate. Both are arranged by moving the row beyond the
        // window rather than by waiting ten minutes.
        // **A stamp that can be told apart, inside the window.** `Timestamps.now()` is truncated to
        // whole seconds, so two calls milliseconds apart write the *same* string: asserting "unchanged"
        // against the first use passed even with the window predicate deleted from the writer. A minute
        // back is a value the second use would overwrite if it wrote at all, and it is still inside the
        // window the throttle uses, so not writing is what the rule asks for.
        String firstUse = Timestamps.format(Instant.now().minusSeconds(60));
        jdbc.sql("UPDATE api_key SET last_used_at = :stamp WHERE user_id = :id")
                .param("stamp", firstUse).param("id", userId).update();

        assertThat(get(ME, token).statusCode()).isEqualTo(200);
        assertThat(lastUsedAt(userId)).as("a hot key must not be a write per request")
                .isEqualTo(firstUse);

        jdbc.sql("UPDATE api_key SET last_used_at = '2000-01-01T00:00:00Z',"
                + " last_used_from = '198.51.100.9' WHERE user_id = :id").param("id", userId).update();
        assertThat(get(ME, token).statusCode()).isEqualTo(200);

        assertThat(lastUsedFrom(userId)).as("the recorded address follows the use")
                .isNotEqualTo("198.51.100.9");
        assertThat(auditsFor(userId, "api_key_new_address"))
                .as("a key used from somewhere new is worth one line in the audit trail").isEqualTo(1);
    }

    @Test
    void aRevokedKeyTurningUpAgainIsRecorded() {
        String userId = registerAndSignIn("returned", randomPhone());
        JsonNode issued = issueKey(keys -> {
            keys.put("name", "came-back");
            keys.put("scopes", List.of("skills:read"));
        });
        String id = issued.get("key").get("id").asText();
        String token = issued.get("token").asText();
        assertThat(revoke(id).statusCode()).isEqualTo(204);

        assertThat(get(ME, token).statusCode()).isEqualTo(401);

        // The second of the two signals ADR 0039 §决定 4 buys, and the cheap half of "a static
        // credential was picked up by somebody": the key was revoked, so this is a machine that kept
        // a copy.
        assertThat(auditsFor(userId, "api_key_revoked_reuse")).isEqualTo(1);
    }

    @Test
    void issuanceRevocationAndRenewalAreAllAudited() {
        String userId = registerAndSignIn("audited", randomPhone());
        String id = issueKey(keys -> {
            keys.put("name", "tracked");
            keys.put("scopes", List.of("skills:read"));
        }).get("key").get("id").asText();

        assertThat(webPost(KEYS + "/" + id + "/rotate", "", csrfToken()).statusCode()).isEqualTo(200);
        String renewedId = jdbc.sql("SELECT id FROM api_key WHERE user_id = :id AND revoked_at IS NULL")
                .param("id", userId).query(String.class).single();
        assertThat(revoke(renewedId).statusCode()).isEqualTo(204);

        assertThat(auditsFor(userId, "api_key_issue")).isEqualTo(1);
        // One line for the renewal rather than a revocation beside an issuance: it is one action the
        // person took, and a trail that spells it as two makes an ordinary renewal look like a key
        // being shut off.
        assertThat(auditsFor(userId, "api_key_rotate")).isEqualTo(1);
        assertThat(auditsFor(userId, "api_key_revoke")).isEqualTo(1);
    }

    /*
     * **A token from the authorization-code flow, and what `/me` says about it, is asserted in
     * `AuthorizationServerIT`.** That is the only place in the suite a real OAuth token comes from
     * now: this class signs keys (ADR 0040 §决定 1 made the browser plane the only one that can mint
     * a credential), so a test here can no longer produce the other kind.
     */

    /*
     * The arrangements and the small readers below. Each asserts what it needed to happen, because an
     * arrangement that silently did not take effect would surface as a puzzling failure in whatever
     * the test was actually about.
     */

    private JsonNode issueKey(java.util.function.Consumer<Map<String, Object>> fields) {
        HttpResponse<String> response =
                webPost(KEYS, json(issueBody(fields)), csrfToken());
        assertThat(response.statusCode()).as("issuing a key: %s", response.body()).isEqualTo(201);
        return body(response);
    }

    /** @return the {@code issue} of the refusal, which the caller asserts against */
    private String issueKeyRefused(java.util.function.Consumer<Map<String, Object>> fields,
            String expectedCode) {
        HttpResponse<String> response = webPost(KEYS, json(issueBody(fields)), csrfToken());
        assertThat(response.statusCode()).as("refusing %s: %s", fields, response.body())
                .isEqualTo(400);
        assertThat(body(response).get("error").get("code").asText()).isEqualTo(expectedCode);
        return issue(response);
    }

    private static Map<String, Object> issueBody(
            java.util.function.Consumer<Map<String, Object>> fields) {
        Map<String, Object> body = new HashMap<>();
        // A default the individual tests override, so that each one only states what it is about.
        body.put("name", "key");
        body.put("scopes", List.of("skills:read"));
        fields.accept(body);
        return body;
    }

    /** Moves a key's date into the past. The clock is not what is under test; the row is. */
    private void expire(String id) {
        int updated = jdbc.sql("UPDATE api_key SET expires_at = '2000-01-01T00:00:00Z'"
                + " WHERE id = :id").param("id", id).update();
        assertThat(updated).as("expiring the key this test just issued").isEqualTo(1);
    }

    private HttpResponse<String> revoke(String id) {
        return webPost(KEYS + "/" + id + "/revoke", "", csrfToken());
    }

    /**
     * Suspends the account the test is acting as, by the id registration returned.
     *
     * <p>Not restored afterwards, and it does not need to be: {@code app_user} survives the truncate
     * between tests — the V2 seed lives there — but every test here registers its own account with a
     * random phone, so a suspended one is read by nothing.
     */
    private void suspend(String userId) {
        int updated = jdbc.sql("UPDATE app_user SET status = 'suspended' WHERE id = :id")
                .param("id", userId).update();
        assertThat(updated).as("suspending the account this test registered").isEqualTo(1);
    }

    private boolean ownerKeyIsStillLive(String id) {
        return jdbc.sql("SELECT count(*) FROM api_key WHERE id = :id AND revoked_at IS NULL")
                .param("id", id).query(Long.class).single() == 1;
    }

    private long auditsFor(String userId, String action) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE actor_user_id = :id AND action = :action")
                .param("id", userId).param("action", action).query(Long.class).single();
    }

    private String lastUsedAt(String userId) {
        return jdbc.sql("SELECT last_used_at FROM api_key WHERE user_id = :id")
                .param("id", userId).query(String.class).single();
    }

    private String lastUsedFrom(String userId) {
        return jdbc.sql("SELECT last_used_from FROM api_key WHERE user_id = :id")
                .param("id", userId).query(String.class).single();
    }

    /**
     * Registers a second account in the same test and signs that browser in as it.
     *
     * <p>Written out here rather than added to the base class: "two accounts" is what the credential
     * tests need and nothing else does, and a helper on the base class would be a helper every future
     * test has to consider.
     */
    private String randomPhoneOfAnotherAccount() {
        // The second code send in one test would otherwise wait out the per-number window or spend
        // an address budget this test has no interest in.
        clearSmsCooldown();
        String phone = randomPhone();
        Browser other = new Browser();
        other.get(uri("/web/session"));
        HttpResponse<String> code = other.post(uri(REGISTER_CODE), codeRequestBody(phone),
                other.csrfToken());
        assertThat(code.statusCode()).as("asking for a second account's code: %s", code.body())
                .isEqualTo(204);
        HttpResponse<String> registered = other.post(uri(REGISTER), json(Map.of(
                "phone", phone, "code", code(phone), "password", PASSWORD, "username", "intruder")),
                other.csrfToken());
        assertThat(registered.statusCode()).as("registering the second account: %s", registered.body())
                .isEqualTo(201);
        return phone;
    }
}
