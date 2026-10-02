package com.skillmasterai.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpCookie;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for the browser plane's tests: a client that behaves like a browser, the codes that were
 * sent, and the arrangements nearly every one of these tests needs.
 *
 * <p><strong>Its own client, with a cookie jar.</strong> {@code AbstractIT}'s is static and has
 * none, which is right for the API plane — a bearer token travels in a header, and a shared jar
 * there would be a shared identity. Here the cookie <em>is</em> the credential, and the jar is the
 * session under test. Per instance rather than static for the same reason: JUnit builds a new test
 * instance per method, so a jar per instance is a jar per test.
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

    /** Long enough for the policy, and equal to none of the generated names or numbers. */
    protected static final String PASSWORD = "correct-horse-battery";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    @Autowired
    protected RecordingSmsSender sms;

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

    /** Registers an account and leaves this client signed in as it. */
    protected void registerAndSignIn(String username, String phone) {
        HttpResponse<String> response = webPost(REGISTER, json(Map.of(
                "phone", phone, "code", requestCode(phone),
                "password", PASSWORD, "username", username)));
        assertThat(response.statusCode())
                .as("registering %s: %s", username, response.body())
                .isEqualTo(201);
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
