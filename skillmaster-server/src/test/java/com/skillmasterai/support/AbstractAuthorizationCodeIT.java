package com.skillmasterai.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * The CLI's browser flow, as a test can drive it: seed a client row, authorize, consent, exchange
 * the code.
 *
 * <p><strong>This is the only way a test can obtain an OAuth credential, and it exists for the tests
 * that are about that kind of credential.</strong> Since ADR 0040 §决定 1 the browser plane is the
 * only one that can mint anything, and what the console and the CLI sign themselves is a key —
 * {@link AbstractAccountIT#token()} does exactly that, in two requests. An OAuth token takes five,
 * so it lives here rather than in the base every test extends: the classes that never mention one
 * should not carry the dance.
 *
 * <p>Which classes need it, and why they cannot use a key instead: M2's validator answers differently
 * for the two kinds ({@code IssuedTokenValidatorIT} pins that apart from {@code ApiKeyIT}), and the
 * authorization server's own lifecycle — codes, refresh, revocation — is only reachable through this
 * flow ({@code AuthorizationServerIT}).
 *
 * <p><strong>A real login, not a seeded session.</strong> The authorize request carries no credential
 * of its own: the framework finds the user in the browser session, which is the whole of the M1→M2
 * boundary (ADR 0014). So the flow below registers an account and signs it in first.
 */
public abstract class AbstractAuthorizationCodeIT extends AbstractAccountIT {

    protected static final String CLIENT_ID = "skillmaster-cli";

    /**
     * A fixed loopback port, and a registered redirect URI that matches it character for character.
     *
     * <p>The design wants an ephemeral port, and the framework will not allow one: it compares the
     * requested redirect URI against the registered set with {@code contains} and knows nothing about
     * RFC 8252 §8.4's exception for the port. That is an open decision, recorded in the module doc;
     * until it is taken, this is the shape that works, and it is also what a deployment would have to
     * seed.
     */
    protected static final String REDIRECT_URI = "http://127.0.0.1:51004/callback";

    protected static final String AUTHORIZE = "/oauth/authorize";
    protected static final String TOKEN = "/oauth/token";
    protected static final String REVOKE = "/oauth/revoke";
    protected static final String CONSENT_PAGE = "/consent";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The CLI's row, seeded by hand — v1 has no registration endpoint and this is what a deployment
     * does instead (ADR 0011, the M02 module doc). {@code ON CONFLICT} because {@code oauth_client} is
     * not truncated between tests: it is a deployment fact, not a fixture.
     */
    protected void seedCliClient() {
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

    /** What a completed flow hands back, and the account it acts as. */
    public record CliSignIn(String userId, String username, String accessToken, String refreshToken) {
    }

    /**
     * Registers a person, signs them in, and drives the whole flow as the CLI does — the arrangement
     * most tests in this family start from, named once because it is the same five requests every
     * time.
     *
     * <p>Asserted as it goes: an arrangement that silently did not take effect would surface as a
     * puzzling failure in whatever the test was actually about.
     */
    protected CliSignIn signInThroughTheCliFlow() {
        seedCliClient();
        String username = randomUsername();
        String userId = registerAndSignIn(username, randomPhone());
        String verifier = codeVerifier();
        String code = consentAndGetCode(verifier, "state-" + randomUsername());
        JsonNode issued = body(tokenRequest(form(Map.of(
                "grant_type", List.of("authorization_code"),
                "code", List.of(code),
                "redirect_uri", List.of(REDIRECT_URI),
                "client_id", List.of(CLIENT_ID),
                "code_verifier", List.of(verifier)))));
        assertThat(issued.hasNonNull("access_token"))
                .as("the token endpoint's answer: %s", issued).isTrue();
        assertThat(issued.hasNonNull("refresh_token"))
                .as("a public client's refresh token: %s", issued).isTrue();
        return new CliSignIn(userId, username, issued.get("access_token").asText(),
                issued.get("refresh_token").asText());
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
    protected String consentAndGetCode(String verifier, String state) {
        HttpResponse<String> toConsent =
                browser.get(uri(AUTHORIZE + "?" + form(authorizationQuery(verifier, state))));
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

    protected static Map<String, List<String>> authorizationQuery(String verifier, String state) {
        return Map.of(
                "response_type", List.of("code"),
                "client_id", List.of(CLIENT_ID),
                "redirect_uri", List.of(REDIRECT_URI),
                "scope", List.of("skills:read skills:write"),
                "state", List.of(state),
                "code_challenge", List.of(challenge(verifier)),
                "code_challenge_method", List.of("S256"));
    }

    protected HttpResponse<String> tokenRequest(String body) {
        return send(formRequest(TOKEN, body));
    }

    /** A form-encoded request to this server; the token plane reads nothing else. */
    protected HttpRequest formRequest(String path, String body) {
        return HttpRequest.newBuilder(uri(path))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /** A form body, with repeated values kept in order — {@code scope} arrives more than once. */
    protected static String form(Map<String, List<String>> fields) {
        List<String> pairs = new ArrayList<>();
        fields.forEach((name, values) -> values.forEach(
                value -> pairs.add(encode(name) + "=" + encode(value))));
        return String.join("&", pairs);
    }

    protected static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    protected static String location(HttpResponse<String> response) {
        return response.headers().firstValue(HttpHeaders.LOCATION)
                .orElseThrow(() -> new AssertionError("no Location header on " + response.statusCode()
                        + "\nheaders: " + response.headers().map()
                        + "\nbody: " + response.body()));
    }

    protected static String queryParameter(String uri, String name) {
        for (String pair : URI.create(uri).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** 43 characters, which is RFC 7636's floor, from 32 bytes of entropy. */
    protected static String codeVerifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    protected static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
