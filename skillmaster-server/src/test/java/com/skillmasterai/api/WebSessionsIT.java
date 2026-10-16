package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Browser;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * v3-sessions' cases T1–T12: the two rulers, and every device being visible and endable (ADR 0043).
 *
 * <p><strong>Every session here is established over HTTP, and the suite never writes a session row
 * to arrange one.</strong> A login is the only thing that produces a session, and it is also what
 * writes the three device attributes the list reads, so a hand-made row would be a row from a shape
 * this version cannot produce. {@code secondSignedInBrowser} is what a second device is.
 *
 * <p><strong>Time, though, is arranged by writing the framework's table</strong> — there is no other
 * way to make a session eight days old, and injecting a clock would replace the very thing under
 * test (that the decision is made from the time in the row) with a decision made from an object.
 * That is allowed on purpose: {@code TableOwnershipTest} scans {@code src/main/java} and the
 * migrations, so a test may write what production code may not — and because it is allowed, every
 * helper below says so, so that nobody reads it as permission for main code.
 *
 * <p>Order matters and is not obvious: Spring Session reads the session at the start of a request,
 * so a row has to be moved <em>before</em> the request that is about it. Reversed, the request is
 * answered from the old values and the test measures nothing.
 *
 * <p>{@code @Sql} on the class, like every other browser-plane IT: {@code spring_session} is in that
 * script, so each case starts with no sessions at all — without it, whichever case ran second would
 * be counting the first one's logins.
 */
@Sql("/sql/truncate-business-tables.sql")
class WebSessionsIT extends AbstractAccountIT {

    /** ADR 0043 §接口's addresses, written out for the reason the constants above are. */
    private static final String SESSIONS = "/web/sessions";

    private static final Duration WEEK = Duration.ofDays(7);

    // ------------------------------------------------------------------ the absolute ceiling

    /** T1 · a session that is eight days old is refused <em>even though it is in use</em>. */
    @Test
    void aSessionPastTheAbsoluteCeilingIsRefusedEvenWhileItIsInUse() {
        String userId = registerAndSignIn(randomUsername(), randomPhone());
        // Created eight days ago, last used a moment ago: the shape the sliding idle rule lets live
        // for ever, and exactly what the old behaviour allowed. This is the regression T1 exists for.
        moveSessionClock(userId, Duration.ofDays(8), Duration.ZERO);

        assertThat(webGet(SESSION).statusCode())
                .as("a session logged in eight days ago must be asked to sign in again")
                .isEqualTo(401);
    }

    /** T2 · a session one hour short of the ceiling is still usable. */
    @Test
    void aSessionJustShortOfTheCeilingStillWorks() {
        String username = randomUsername();
        String userId = registerAndSignIn(username, randomPhone());
        // Six days and twenty-three hours. This is the case a ceiling computed a second or an hour
        // wrong still passes everywhere else — accept #2's whole reason for existing.
        moveSessionClock(userId, WEEK.minusHours(1), Duration.ZERO);

        HttpResponse<String> response = webGet(SESSION);
        assertThat(response.statusCode()).as("six days and twenty-three hours: %s", response.body())
                .isEqualTo(200);
        assertThat(body(response).get("username").asText()).isEqualTo(username);
    }

    /** T12 · and the row of the session T1 refuses is gone from the store, not merely refused. */
    @Test
    void theRowOfASessionPastTheCeilingIsGoneRatherThanIgnored() {
        String userId = registerAndSignIn(randomUsername(), randomPhone());
        moveSessionClock(userId, Duration.ofDays(8), Duration.ZERO);
        assertThat(sessionRows(userId)).as("the login wrote a row").isEqualTo(1);

        assertThat(webGet(SESSION).statusCode()).isEqualTo(401);

        // The difference that matters: read-time enforcement that only refuses leaves a live session
        // sitting in the store for anybody who reads the table, and for the next request that has a
        // different idea about the clock.
        assertThat(sessionRows(userId)).as("the refused session's row").isZero();
    }

    // ----------------------------------------------------------------------- the idle window

    /** T3 · a session untouched for longer than the window is refused. */
    @Test
    void aSessionIdlePastTheWindowIsRefused() {
        String userId = registerAndSignIn(randomUsername(), randomPhone());
        moveSessionClock(userId, Duration.ofDays(3), Duration.ofHours(25));

        assertThat(webGet(SESSION).statusCode())
                .as("twenty-five hours with no request")
                .isEqualTo(401);
    }

    /** T4 · a session untouched for less than the window is fine. */
    @Test
    void aSessionIdleJustInsideTheWindowStillWorks() {
        String userId = registerAndSignIn(randomUsername(), randomPhone());
        moveSessionClock(userId, Duration.ofDays(3), Duration.ofHours(23));

        assertThat(webGet(SESSION).statusCode())
                .as("twenty-three hours with no request")
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------------- ending a device

    /** T5 · ending another device ends it, and does not touch the one asking. */
    @Test
    void endingAnotherDeviceEndsItAndLeavesTheOneAsking() {
        String username = randomUsername();
        String phone = randomPhone();
        registerAndSignIn(username, phone);
        Browser second = secondSignedInBrowser(phone, PASSWORD);
        String itsDevice = deviceIdOf(second);

        HttpResponse<String> response =
                webPost(SESSIONS + "/" + itsDevice + "/revoke", "", csrfToken());
        assertThat(response.statusCode()).as("ending the other device: %s", response.body())
                .isEqualTo(204);

        assertThat(second.get(uri(SESSION)).statusCode())
                .as("the ended device's next request is refused")
                .isEqualTo(401);
        assertThat(webGet(SESSION).statusCode()).as("the device that asked is untouched")
                .isEqualTo(200);
    }

    /** T6 · and it is refused <em>immediately</em> — nothing waits for a sweep that has not run. */
    @Test
    void anEndedDeviceIsRefusedWithoutWaitingForAnySweep() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        Browser second = secondSignedInBrowser(phone, PASSWORD);
        String userId = userIdOf();
        String itsDevice = deviceIdOf(second);

        assertThat(sessionRows(userId)).as("two devices are signed in").isEqualTo(2);

        webPost(SESSIONS + "/" + itsDevice + "/revoke", "", csrfToken());

        // No cleanup task is triggered anywhere in this suite, and none is configured to run on a
        // schedule the test could wait for. So the row going now, and the next request being
        // refused, is the whole of the mechanism: the sweep the framework has is for tidying, and
        // an implementation that relied on it would fail right here.
        assertThat(sessionRows(userId)).as("the row is gone, not marked").isEqualTo(1);
        assertThat(second.get(uri(SESSION)).statusCode()).isEqualTo(401);
    }

    /** T7 · ending every other device ends all of them, leaves this one, and says how many. */
    @Test
    void endingEveryOtherDeviceEndsThemAllAndReportsTheCount() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        Browser second = secondSignedInBrowser(phone, PASSWORD);
        Browser third = secondSignedInBrowser(phone, PASSWORD);

        HttpResponse<String> response = webPost(SESSIONS + "/revoke-others", "", csrfToken());
        assertThat(response.statusCode()).as("ending the others: %s", response.body()).isEqualTo(200);
        assertThat(body(response).get("revoked").asInt())
                .as("two other devices were signed in")
                .isEqualTo(2);

        assertThat(second.get(uri(SESSION)).statusCode()).isEqualTo(401);
        assertThat(third.get(uri(SESSION)).statusCode()).isEqualTo(401);
        assertThat(webGet(SESSION).statusCode())
                .as("the device that asked must survive its own request")
                .isEqualTo(200);
        assertThat(sessionsOf(browser)).as("and only it is left in the list").hasSize(1);
    }

    // ------------------------------------------------------------------------------ the trace

    /** Q5 · ending a login leaves a trace, in the shape the key screen's revocations already do. */
    @Test
    void endingADeviceAndEndingTheOthersAreBothRecorded() {
        String phone = randomPhone();
        String userId = registerAndSignIn(randomUsername(), phone);
        Browser second = secondSignedInBrowser(phone, PASSWORD);
        Browser third = secondSignedInBrowser(phone, PASSWORD);
        String theirs = deviceIdOf(second);

        webPost(SESSIONS + "/" + theirs + "/revoke", "", csrfToken());
        webPost(SESSIONS + "/revoke-others", "", csrfToken());

        assertThat(count("SELECT COUNT(*) FROM audit_event WHERE action = 'session_revoke'"
                + " AND actor_user_id = :id", Map.of("id", userId)))
                .as("one row for the device ended by id, one for the one ended in bulk")
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM audit_event WHERE action = 'session_revoke'"
                + " AND target_id = :device", Map.of("device", theirs)))
                .as("the single revocation names the device it ended, which is the handle the"
                        + " console also addresses — not a session id")
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_event WHERE action = 'session_revoke'"
                + " AND detail LIKE :detail", Map.of("detail", "%revoke-others%")))
                .as("and the bulk one is distinguishable, so \"what happened to that login\" can"
                        + " be answered without guessing")
                .isEqualTo(1);
    }

    // --------------------------------------------------------------------------- what it shows

    /** Accepting #6 · the list says what a device is without ever naming a session. */
    @Test
    void theListDescribesEachDeviceAndCarriesNoSessionId() {
        String phone = randomPhone();
        String username = randomUsername();
        String userId = registerAndSignIn(username, phone);
        secondSignedInBrowser(phone, PASSWORD);

        JsonNode sessions = sessionsOf(browser);
        assertThat(sessions).as("both devices").hasSize(2);

        JsonNode mine = currentRowOf(sessions);
        assertThat(mine.get("current").asBoolean()).isTrue();
        assertThat(mine.get("device_id").asText())
                .as("the handle the console addresses — a ULID this service minted")
                .isNotBlank();
        assertThat(mine.get("device").asText())
                .as("the User-Agent as it arrived. The suite's client is not a browser, so this is"
                        + " the fallback branch: an unrecognised agent is shown as it came")
                .isNotBlank();
        assertThat(mine.get("address").asText()).as("where the login came from").isNotBlank();
        assertThat(mine.get("logged_in_at").asText()).isNotBlank();
        assertThat(mine.get("last_seen_at").asText()).isNotBlank();

        // Exactly one row is the current one — the row that asked.
        int marked = 0;
        for (JsonNode row : sessions) {
            if (row.get("current").asBoolean()) {
                marked++;
            }
        }
        assertThat(marked).as("exactly one device is the one asking").isEqualTo(1);

        // **The response carries no session id**, which is the half a shape assertion cannot make:
        // the ids are read out of the store and looked for in the body as text.
        List<String> stored = sessionIdsInStore(userId);
        assertThat(stored).as("two sessions are stored for this account").hasSize(2);
        String raw = webGet(SESSIONS).body();
        for (String id : stored) {
            assertThat(raw).as("session id %s must not leave the server", id).doesNotContain(id);
        }
    }

    /** T9 · somebody else's device, and a device that never existed, get one answer and change nothing. */
    @Test
    void endingSomebodyElsesDeviceDoesNothingAndSaysSoTheSameWay() {
        registerAndSignIn(randomUsername(), randomPhone());
        Browser theirs = registerInAnotherBrowser(randomUsername(), randomPhone());
        String theirDevice = deviceIdOf(theirs);

        HttpResponse<String> notMine = webPost(SESSIONS + "/" + theirDevice + "/revoke", "",
                csrfToken());
        HttpResponse<String> noSuchThing = webPost(SESSIONS + "/" + "01M3HTG7GCCVBGRPAFFSVSF12W"
                + "/revoke", "", csrfToken());

        // One answer for both, and the same one, because telling them apart would confirm that a
        // device with that id exists (accepting #7).
        assertThat(notMine.statusCode()).isEqualTo(404);
        assertThat(noSuchThing.statusCode()).isEqualTo(404);
        assertThat(body(notMine).get("error").get("code").asText())
                .isEqualTo(body(noSuchThing).get("error").get("code").asText())
                .isEqualTo("session_not_found");
        assertThat(theirs.get(uri(SESSION)).statusCode())
                .as("the other account's session is untouched")
                .isEqualTo(200);
    }

    /** T10 · resetting the password still ends every session, and the list says so. */
    @Test
    void aPasswordResetEndsEverySessionAndEmptiesTheList() {
        String phone = randomPhone();
        registerAndSignIn(randomUsername(), phone);
        Browser second = secondSignedInBrowser(phone, PASSWORD);
        String reset = "reset-correct-horse";

        // Two messages to one number inside the cooldown's window; the cooldown is pinned elsewhere
        // and is not what this case is about.
        clearSmsCooldown();
        String code = requestCode(RESET_CODE, phone);
        HttpResponse<String> response = webPost(RESET, json(Map.of(
                "phone", phone, "code", code, "password", reset)));
        assertThat(response.statusCode()).as("resetting: %s", response.body()).isEqualTo(204);

        assertThat(webGet(SESSION).statusCode()).as("the first device").isEqualTo(401);
        assertThat(second.get(uri(SESSION)).statusCode()).as("the second device").isEqualTo(401);

        // And the list agrees, which is the half accepting #8 asks for: a person signing back in
        // sees one device and not the two that were just ended.
        assertThat(signIn(phone, reset).statusCode()).isEqualTo(200);
        assertThat(sessionsOf(browser)).as("only the new login").hasSize(1);
    }

    // --------------------------------------------------------------------------------- helpers

    /** How many session rows this account has — the store's answer, not the list's. */
    private long sessionRows(String userId) {
        return count("SELECT COUNT(*) FROM spring_session WHERE principal_name = :id",
                Map.of("id", userId));
    }

    /** The raw session ids, for the one assertion that is about what must not be sent. */
    private List<String> sessionIdsInStore(String userId) {
        return jdbc.sql("SELECT session_id FROM spring_session WHERE principal_name = :id")
                .param("id", userId)
                .query(String.class)
                .list();
    }

    /** This account's logins, read through the jar that is signed in. */
    private JsonNode sessionsOf(Browser who) {
        HttpResponse<String> response = who.get(uri(SESSIONS));
        assertThat(response.statusCode()).as("listing sessions: %s", response.body()).isEqualTo(200);
        return body(response).get("sessions");
    }

    /** The device id of the session this browser is holding — the row it is looking at from inside. */
    private String deviceIdOf(Browser who) {
        JsonNode mine = currentRowOf(sessionsOf(who));
        String device = mine.get("device_id").asText();
        assertThat(device).as("a session the list can address").isNotBlank();
        return device;
    }

    private static JsonNode currentRowOf(JsonNode sessions) {
        for (JsonNode session : sessions) {
            if (session.get("current").asBoolean()) {
                return session;
            }
        }
        throw new AssertionError("the list marked no row as the current device: " + sessions);
    }

    /**
     * Registers an account on a browser of its own, so that one test can act as two people.
     *
     * <p>The code is requested over the primary jar because a verification code belongs to a phone
     * number and not to a client — but the registration itself goes through the new jar, which is
     * what makes its session a different account's.
     */
    private Browser registerInAnotherBrowser(String username, String phone) {
        Browser other = new Browser();
        // A fresh client has no CSRF token until it has read something, exactly as a browser does.
        other.get(uri(SESSION));
        HttpResponse<String> created = other.post(uri(REGISTER), json(Map.of(
                        "phone", phone, "code", requestCode(phone),
                        "password", PASSWORD, "username", username)),
                other.csrfToken());
        assertThat(created.statusCode())
                .as("registering %s: %s", username, created.body())
                .isEqualTo(201);
        return other;
    }

    /** The user id of whoever the primary jar is signed in as, read the way the console would. */
    private String userIdOf() {
        return body(webGet(SESSION)).get("user_id").asText();
    }
}
