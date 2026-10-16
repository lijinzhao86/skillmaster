package com.skillmasterai.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpCookie;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.account.PasswordHasher;
import com.skillmasterai.modules.account.PhoneCipher;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/**
 * Base for every test that needs an account: a client that behaves like a browser, the codes that
 * were sent, and the arrangements nearly every one of these tests needs.
 *
 * <p><strong>Its own client, with a cookie jar.</strong> {@code AbstractIT}'s is static and has
 * none, which is right for a request that carries a bearer token — a shared jar there would be a
 * shared identity. Here the cookie <em>is</em> a credential, and the jar is the session under test.
 * Per instance rather than static for the same reason: JUnit builds a new test instance per method,
 * so a jar per instance is a jar per test.
 *
 * <p><strong>Both planes, because since ADR 0040 §决定 1 a credential comes from this one.</strong>
 * The API-plane tests extend this class to obtain their token ({@link #token()}), which is signed
 * over a session here rather than minted by a client of their own — see that method for why a test
 * may not write an {@code api_key} row itself. {@code AbstractAuthorizationCodeIT} adds the browser
 * flow on top, for the tests that need the other kind of credential.
 */
public abstract class AbstractAccountIT extends AbstractIT {

    /*
     * The browser plane's paths, written out rather than taken from WebRoutes: that class is
     * package-private to `api` on purpose, and a test able to read it would be a test that passed
     * because both sides moved together. These are the documented addresses; a change to either
     * side should break here.
     */
    protected static final String CAPTCHA = "/web/captcha";
    protected static final String REGISTER_CODE = "/web/register/code";
    protected static final String REGISTER = "/web/register";
    protected static final String LOGIN = "/web/login";
    protected static final String LOGOUT = "/web/logout";
    protected static final String RESET_CODE = "/web/reset/code";
    protected static final String RESET = "/web/reset";
    protected static final String SESSION = "/web/session";

    /** Where a session signs a credential. The only way one comes into existence (ADR 0040 §决定 1). */
    protected static final String KEYS = "/web/keys";

    /**
     * The way in this suite gives the seeded account, and it is the harness's own fact — no product
     * value, no seeded row. The number is fixed rather than random so that a failure names a phone
     * somebody can look up; nothing else in the suite registers it.
     */
    /** The seeded account's handle — what {@code /web/session} answers with once it is signed in. */
    private static final String SEEDED_HANDLE = "demo";

    private static final String SEEDED_PHONE = "13000000001";
    private static final String SEEDED_PASSWORD = "harness-correct-horse";

    /** Long enough for the policy, and equal to none of the generated names or numbers. */
    protected static final String PASSWORD = "correct-horse-battery";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    @Autowired
    protected RecordingSmsSender sms;

    /** The two halves of a login this suite has to be able to write: how a phone is hashed, and how
     *  a password is. Injected rather than re-implemented — a copy of either would drift from the
     *  one login actually reads, and the symptom would be a 401 nobody could explain. */
    @Autowired
    protected PhoneCipher phoneCipher;

    @Autowired
    protected PasswordHasher passwordHasher;

    /** Read from the property the server reads, so a rename cannot leave a test looking at nothing. */
    @Value("${server.servlet.session.cookie.name}")
    protected String sessionCookieName;

    protected final Browser browser = new Browser();

    /**
     * Gets this client its CSRF token, which every client on this plane has to do before its first
     * write.
     *
     * <p>The token is a cookie and the write has to carry the same value in a header, so a client
     * that has never read anything has nothing to send. Reading the session first is both the
     * documented way to obtain one and the request a client would make anyway — it is how it learns
     * whether it is already signed in. {@code WebCsrfIT} pins the whole of that exchange from
     * outside, including that the cookie appears on a response to a client that had none.
     */
    @BeforeEach
    void obtainCsrfToken() {
        webGet(SESSION);
    }

    protected HttpResponse<String> webPost(String path, String body) {
        return browser.post(uri(path), body);
    }

    /** @param csrfToken null to send no CSRF header — the omission several of these tests are about */
    protected HttpResponse<String> webPost(String path, String body, String csrfToken) {
        return browser.post(uri(path), body, csrfToken);
    }

    /** A GET through the same jar — how a client reads back who it is signed in as. */
    protected HttpResponse<String> webGet(String path) {
        return browser.get(uri(path));
    }

    protected String csrfToken() {
        return browser.csrfToken();
    }

    protected HttpCookie cookie(String name) {
        return browser.cookie(name).orElse(null);
    }

    protected String cookieValue(String name) {
        HttpCookie cookie = cookie(name);
        return cookie == null ? null : cookie.getValue();
    }

    /**
     * The code that was sent to this number.
     *
     * @throws AssertionError when none was sent — a wiring failure, not a test failure, and it must
     *         not read as "the code was empty"
     */
    protected String code(String phone) {
        String sent = sms.lastCode(phone);
        if (sent == null) {
            throw new AssertionError("no verification code was sent to " + phone);
        }
        return sent;
    }

    /** A request body. Keys go on the wire as written; every field of these requests is one word. */
    protected String json(Map<String, ?> fields) {
        return JSON.writeValueAsString(fields);
    }

    protected JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    /**
     * The field a refusal named, and the issue it named it with.
     *
     * <p>Both assume the shape the field errors actually have — a non-empty {@code details} — so a
     * response that carries none fails here rather than at the assertion that follows, which would
     * otherwise report on whatever node was there instead.
     */
    protected String field(HttpResponse<String> response) {
        return body(response).get("error").get("details").get(0).get("field").asText();
    }

    protected String issue(HttpResponse<String> response) {
        return body(response).get("error").get("details").get(0).get("issue").asText();
    }

    /*
     * The four arrangements below are here rather than repeated in each test because almost all of
     * them need the same ones, and each is the same several requests every time. They assert as they
     * go: an arrangement that silently did not take effect would surface as a puzzling failure in
     * whatever the test was actually about.
     */

    /**
     * Asks for a captcha and returns the id to answer it with.
     *
     * <p>Every request that sends a code needs one, which is why these three helpers exist: the
     * production answer is drawn from a {@code SecureRandom} inside an image, so a test can only
     * know it through {@link RecordingCaptchaRenderer}.
     */
    protected String issueCaptcha() {
        HttpResponse<String> response = webGet(CAPTCHA);
        assertThat(response.statusCode()).as("requesting a captcha: %s", response.body())
                .isEqualTo(200);
        assertThat(body(response).get("image").asText()).as("the captcha image").isNotBlank();
        return body(response).get("captcha_id").asText();
    }

    /** A body for either code-sending endpoint, with a fresh captcha solved. */
    protected String codeRequestBody(String phone) {
        return codeRequestBody(phone, issueCaptcha());
    }

    /** A body for a challenge that was already issued. */
    protected String codeRequestBody(String phone, String captchaId) {
        return codeRequestBody(phone, captchaId, RecordingCaptchaRenderer.ANSWER);
    }

    protected String codeRequestBody(String phone, String captchaId, String captchaAnswer) {
        // Snake case written out: the tests send raw maps, so no naming strategy applies to them.
        return json(Map.of("phone", phone, "captcha_id", captchaId,
                "captcha_answer", captchaAnswer));
    }

    /** Asks for a registration code and returns it. */
    protected String requestCode(String phone) {
        return requestCode(REGISTER_CODE, phone);
    }

    /** Asks for a code at either endpoint — registration's or password reset's. */
    protected String requestCode(String path, String phone) {
        HttpResponse<String> response = webPost(path, codeRequestBody(phone));
        assertThat(response.statusCode())
                .as("requesting a code at %s for %s: %s", path, phone, response.body())
                .isEqualTo(204);
        return code(phone);
    }

    /**
     * Moves the SMS cooldown's counter out of the way.
     *
     * <p>That rule is one message per number per minute, which any test needing two messages would
     * otherwise have to wait out. Moving the window lets such a test reach the rule it is actually
     * about; the cooldown itself is pinned in {@code AccountThrottleIT}, and nothing else depends
     * on it having been respected here.
     */
    protected void clearSmsCooldown() {
        jdbc.sql("UPDATE auth_throttle SET window_start = '2000-01-01T00:00:00Z'"
                + " WHERE scope = 'sms:cooldown'").update();
    }

    /**
     * Uses up the one captcha-free registration send this client's address gets in the window.
     *
     * <p>Arranged by making that send rather than by writing the row: the window is the server's to
     * compute, and a test that recomputed it would be testing its own arithmetic. Every test below
     * that is about the captcha needs this first — otherwise the free send answers the request before
     * the challenge is ever read, and a test about a refused answer passes because nothing asked for
     * one.
     *
     * <p>It sends a message to a throwaway number, which is what spending the allowance means. That
     * also spends the address's SMS budget, so a test that deliberately reaches the address cap has
     * to move that row rather than count what this left behind.
     */
    protected void spendFreeCodeSend() {
        HttpResponse<String> response =
                webPost(REGISTER_CODE, json(Map.of("phone", randomPhone())));
        assertThat(response.statusCode()).as("spending the free send: %s", response.body())
                .isEqualTo(204);
    }

    /**
     * Registers an account and leaves this client signed in as it.
     *
     * @return the new account's user id, which {@code tokenFor} turns into an API-plane credential.
     *         Returned rather than looked up so that a test can act as this person on both planes
     *         without a second query that could disagree with what registration actually made
     */
    protected String registerAndSignIn(String username, String phone) {
        HttpResponse<String> response = webPost(REGISTER, json(Map.of(
                "phone", phone, "code", requestCode(phone),
                "password", PASSWORD, "username", username)));
        assertThat(response.statusCode())
                .as("registering %s: %s", username, response.body())
                .isEqualTo(201);
        return JSON.readTree(response.body()).get("user_id").asText();
    }

    /**
     * A bearer token for the **seeded** account, obtained the way a client obtains one.
     *
     * <p><strong>Two steps, and both are real endpoints</strong>: sign in on the browser plane, then
     * sign a key over that session. That is the only way a credential comes into existence — since
     * ADR 0040 §决定 1 the browser plane is the only one that can mint one — so a suite that wrote an
     * {@code api_key} row directly would stop exercising the very path its tokens depend on, which is
     * exactly what the previous shape (a hand-seeded {@code client_credentials} client) was there to
     * avoid.
     *
     * <p>The seeded accounts are deliberately unloginnable — V2 writes them with no credential and no
     * phone, and login is by {@code app_user.phone_hash} — so {@link #signInSeededUser} gives {@code
     * demo} both. That is a test doing what registration does, not a test reaching around a rule.
     */
    protected String token() {
        return token(Scopes.SKILLS_READ, Scopes.SKILLS_WRITE);
    }

    /**
     * A key carrying exactly these scopes.
     *
     * <p>Narrowing is a request for fewer scopes, which the server honours because they are a subset
     * of what the account may ask for — so a test about the 403 challenge can have a credential that
     * is missing one without a server configured differently from every other test's.
     */
    protected String token(String... scopes) {
        // **The guard, and why it is not paranoia.** Signing in here re-points the cookie jar — the
        // one this test instance has been using — at the seeded account. A test that registered
        // somebody, still needs the browser, and then asks for this credential ends up holding a
        // credential for `demo` while every later assertion is about the other person. Nothing did
        // that on 2026-10-16; the invariant it breaks is one edit away (`webGet(SESSION)` after
        // `token()`), and the failure would read as a wrong answer rather than as a wrong identity.
        // `tokenFor` makes the same check from the other side.
        String signedIn = signedInAs();
        assertThat(signedIn == null || signedIn.equals(SEEDED_HANDLE))
                .as("token() signs the seeded account in over this jar — a test that registered an"
                        + " account and still needs the browser afterwards must use tokenFor()")
                .isTrue();
        signInSeededUser();
        return signedKey(scopes);
    }

    /**
     * A bearer token for an account this suite created, rather than for the seeded one.
     *
     * <p>Exists because the planes had no way to act as the same person: a test that submits over the
     * API and then acts on the result in a browser would have two different accounts and no way to
     * notice. ADR 0031 made that shape necessary — submitting is an API-plane act and publishing is a
     * browser-plane one, and they are the same person's.
     *
     * <p>The caller has just signed this person in (that is what {@link #registerAndSignIn} returns
     * the id for), so the key is signed over that session — and the id is **checked against the
     * session** rather than taken on trust, because a key belonging to somebody else would turn every
     * later assertion into a claim about the wrong person.
     */
    protected String tokenFor(String userId) {
        String handle = jdbc.sql("SELECT handle FROM app_user WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
        assertThat(sessionUsername())
                .as("tokenFor(%s) signs over the session in the jar — sign that person in first", userId)
                .isEqualTo(handle);
        return signedKey(Scopes.SKILLS_READ, Scopes.SKILLS_WRITE);
    }

    /** The same GET, with this suite's own credential filled in. */
    protected HttpResponse<byte[]> getBytes(String path) {
        return getBytes(path, token());
    }

    /** Signs a key over the current session. Its plaintext is shown once, which is why it comes back. */
    private String signedKey(String... scopes) {
        HttpResponse<String> response = webPost(KEYS, json(Map.of(
                "name", "harness",
                "scopes", List.of(scopes))), csrfToken());
        assertThat(response.statusCode())
                .as("signing a key for the suite: %s", response.body())
                .isEqualTo(201);
        return JSON.readTree(response.body()).get("token").asText();
    }

    /** Who the jar is signed in as, read back from the endpoint a client would use. */
    private String sessionUsername() {
        return JSON.readTree(webGet(SESSION).body()).get("username").asText();
    }

    /** Who the jar is signed in as, or null when nobody is — the 401 is an answer, not a failure. */
    private String signedInAs() {
        var session = webGet(SESSION);
        return session.statusCode() == 200
                ? JSON.readTree(session.body()).get("username").asText()
                : null;
    }

    /**
     * Gives the seeded account a phone number and a password, then signs it in.
     *
     * <p>Written the way registration writes them — {@code phone_hash} on {@code app_user}, a
     * {@code password} row in {@code credential} — because that is the shape login reads. Once per
     * test method: the truncate script between tests empties {@code credential}, so a session left
     * over from the previous method would not be this method's.
     */
    private void signInSeededUser() {
        // Both phone columns, because `app_user_phone_paired` refuses a row with one and not the
        // other — a state no registration path produces, so no reading code decides what it means.
        // `phone_enc` is what shows a number back to its owner; the hash is what login looks up.
        jdbc.sql("UPDATE app_user SET phone_hash = :hash, phone_enc = :enc WHERE id = :id")
                .param("hash", phoneCipher.hash(SEEDED_PHONE))
                .param("enc", phoneCipher.encrypt(SEEDED_PHONE))
                .param("id", SUBJECT_USER_ID)
                .update();
        jdbc.sql("INSERT INTO credential (user_id, type, secret_hash, updated_at)"
                        + " VALUES (:id, 'password', :hash, :now)"
                        + " ON CONFLICT (user_id, type)"
                        + " DO UPDATE SET secret_hash = EXCLUDED.secret_hash,"
                        + " updated_at = EXCLUDED.updated_at")
                .param("id", SUBJECT_USER_ID)
                .param("hash", passwordHasher.hash(SEEDED_PASSWORD))
                .param("now", Timestamps.now())
                .update();
        assertThat(signIn(SEEDED_PHONE, SEEDED_PASSWORD).statusCode())
                .as("signing the seeded account in")
                .isEqualTo(200);
    }

    protected HttpResponse<String> signIn(String phone, String password) {
        return webPost(LOGIN, json(Map.of("phone", phone, "password", password)));
    }

    /**
     * A second signed-in browser — a second session for the same account.
     *
     * <p>For the tests about a user's sessions rather than about one of them: ending all of them is
     * only a claim a test can make if there was more than one. It bootstraps its CSRF token the way
     * any fresh client has to, by reading one off a response first.
     */
    protected Browser secondSignedInBrowser(String phone, String password) {
        Browser other = new Browser();
        other.get(uri(SESSION));
        HttpResponse<String> response = other.post(uri(LOGIN),
                json(Map.of("phone", phone, "password", password)), other.csrfToken());
        assertThat(response.statusCode())
                .as("signing a second browser in: %s", response.body())
                .isEqualTo(200);
        return other;
    }

    /**
     * How long a row of {@code captcha} or {@code phone_verification} stays good for, in seconds.
     *
     * <p>Read back as the difference between the two timestamps the row carries, rather than against
     * a clock: the lifetime is what decides how long a solved-but-unused answer stays valid, and
     * asserting it against `Instant.now()` would also be asserting how long the test took to run.
     * The table name is a literal at both call sites — it cannot be a parameter without making this
     * helper the one place in the suite that builds SQL from a string.
     *
     * @param table the table to read; one of the two named above, both of which carry these columns
     */
    protected long lifetimeSecondsOf(String table) {
        return jdbc.sql("SELECT CAST(EXTRACT(EPOCH FROM (CAST(expires_at AS timestamptz)"
                        + " - CAST(created_at AS timestamptz))) AS bigint) FROM " + table)
                .query(Long.class)
                .single();
    }

    /**
     * A plausible idle window, in milliseconds — the framework's unit, since {@code expiry_time} is
     * epoch millis.
     *
     * <p><strong>Not this deployment's number, and nothing that uses it depends on it being that.</strong>
     * The cases that move a session's clock write all three columns themselves, and the read path that
     * refuses a session works from the row rather than from {@code expiry_time} — so this only has to
     * be a value a real row could carry. What the deployment actually ships is pinned in
     * {@code SessionPolicyStartupIT}.
     *
     * <p><strong>Which column it works from is worth stating exactly, because the wrong version of
     * this sentence is easy to write and hides a real fact about the deployment.</strong> Spring
     * Session reads {@code max_inactive_interval} out of the row into a {@code MapSession} and asks
     * whether {@code now - maxInactiveInterval >= lastAccessedTime} — so the window that decides an
     * existing session's fate is <em>the row's own copy</em>, fixed when the session was inserted
     * from {@code spring.session.timeout}, and not whatever the configuration says today. The
     * consequence for an operator: <strong>retuning {@code SKILLMASTER_SESSIONS_IDLE} re-times the
     * logins that come after it, not the ones already sitting in the table</strong> — a session
     * created under the old value keeps expiring by the old one. ({@code expiry_time} is not read at
     * all on the read path; it is what the framework writes so that its own cleanup task can find
     * the rows to delete.)
     */
    protected static final long IDLE_MILLIS = Duration.ofHours(24).toMillis();

    /**
     * Moves a session's clock in the framework's table, by its owner.
     *
     * <p><strong>This is a test writing {@code spring_session}, which main code may not do.</strong>
     * The permission is {@code TableOwnershipTest}'s scope — {@code src/main/java} and the migrations —
     * and it is not a precedent for anything outside this package. It is also the reason the v3 test
     * plan chose "write the row" over "inject a clock": the rule under test is "the decision comes from
     * the time in the row", and a seam would take that decision away from the row.
     *
     * <p>Both times are moved at once because they are two different rulers over one row: the ceiling
     * reads {@code creation_time} and the framework's idle rule reads {@code last_access_time}.
     * {@code expiry_time} is written too, to the value the framework itself would have written (last
     * access plus the idle window) — leaving it in the past while pretending the session is alive
     * would be a row no real state produces.
     *
     * <p>Order matters and is not obvious: Spring Session reads the session at the start of a request,
     * so a row has to be moved <em>before</em> the request that is about it. Reversed, the request is
     * answered from the old values and the test measures nothing.
     *
     * @param createdAgo how long ago the login happened — what the absolute ceiling counts from
     * @param seenAgo    how long ago the session was last used — what the idle window counts from
     */
    protected void moveSessionClock(String userId, Duration createdAgo, Duration seenAgo) {
        long now = System.currentTimeMillis();
        long seen = now - seenAgo.toMillis();
        int moved = jdbc.sql("UPDATE spring_session SET creation_time = :created,"
                        + " last_access_time = :seen, expiry_time = :expiry"
                        + " WHERE principal_name = :id")
                .param("created", now - createdAgo.toMillis())
                .param("seen", seen)
                .param("expiry", seen + IDLE_MILLIS)
                .param("id", userId)
                .update();
        assertThat(moved).as("a session to move for %s", userId).isEqualTo(1);
    }

    /** A number no other test will use, in the shape the phone policy accepts. */
    protected static String randomPhone() {
        return "1" + (3 + RANDOM.nextInt(7)) + String.format("%09d", RANDOM.nextInt(1_000_000_000));
    }

    /**
     * A username no other test will use.
     *
     * <p>Lower-case and digits only, so that it is equally a legal namespace slug — which it becomes
     * the moment an account is created, and a name legal as one and not the other would fail the
     * registration rather than the assertion.
     */
    protected static String randomUsername() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "u" + HEX.formatHex(bytes);
    }
}
