package com.skillmasterai.support;

import com.skillmasterai.config.SkillmasterProperties;
import com.skillmasterai.modules.auth.Scopes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for tests that exercise the real HTTP stack.
 *
 * <p>A random port and a real client rather than MockMvc, because a good part of what these
 * tests are for — how Tomcat treats an encoded slash or a traversal attempt, what headers
 * actually reach the wire — does not exist in a mock servlet environment.
 *
 * <p>Clients are plain {@link HttpClient} rather than a Spring test client: the assertions are
 * about status codes and headers, which need no framework support, and this keeps the tests
 * immune to the test-client API churn between Spring Boot majors.
 *
 * <p>Requires the test database — run {@code scripts/init-test-db.sh} first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(CleanMigrateFlyway.class)
public abstract class AbstractIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** For reading the token endpoint's answer, which is one field of one object. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    protected SkillmasterProperties properties;

    /**
     * Direct database access, for the parts of the contract that HTTP cannot show.
     *
     * <p>Publishing idempotence, blob de-duplication and soft deletion are all statements about
     * stored rows — a response body can be right while the row it should have written is missing,
     * or present twice. Asserting on the rows is what makes those tests about the behaviour rather
     * than about the serialisation.
     */
    @Autowired
    protected JdbcClient jdbc;

    @Value("${local.server.port}")
    protected int port;

    /** @param params named parameters, in the order the SQL names them */
    protected long count(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    protected long count(String sql) {
        return count(sql, Map.of());
    }

    /** The `demo` user seeded by V2__seed_owner_and_namespaces.sql — who the API tokens act as. */
    protected static final String SUBJECT_USER_ID = "01M3HTG7GCCVBGRPAFFSVSF12W";

    /**
     * The unattended client the suite mints its tokens with, and its secret.
     *
     * <p>Seeded here rather than in a migration because **v1 registers clients by hand at
     * deployment** — there is no registration endpoint (ADR 0011) — so this is a test doing what a
     * deployment does, not a test reaching around the design. It is a {@code client_credentials}
     * client bound to the seeded user (ADR 0022), which is the only way to get a token with no
     * browser and no password: the seeded accounts have no credentials, because the truncate script
     * between tests empties that table.
     *
     * <p><strong>The stored secret is a real bcrypt hash, not `{noop}`</strong>, and that is not
     * decoration. `ClientSecretAuthenticationProvider` calls `PasswordEncoder.upgradeEncoding` on
     * whatever it finds and, when the answer is yes, re-encodes the secret and calls
     * `RegisteredClientRepository.save` — a call this module refuses, because v1 has no way to
     * register a client. A `{noop}` row therefore fails authentication with an
     * `UnsupportedOperationException` from a stack nobody expects. `{bcrypt}` is what the column is
     * documented to hold, and it is also the encoding the framework leaves alone.
     */
    protected static final String UNATTENDED_CLIENT_ID = "skillmaster-test-unattended";
    private static final String UNATTENDED_CLIENT_SECRET = "test-client-secret";
    private static final String UNATTENDED_CLIENT_SECRET_HASH =
            "{bcrypt}" + new BCryptPasswordEncoder().encode(UNATTENDED_CLIENT_SECRET);

    /** Minted once per test instance, which JUnit makes one per test method. */
    private String mintedToken;
    private String mintedScopes;

    /**
     * A bearer token for {@link #SUBJECT_USER_ID}, obtained the way a client obtains one.
     *
     * <p><strong>Minted, not configured.</strong> P0 resolved a single token out of
     * {@code application.yml}; P1 replaced it with tokens the authorization server issues, and that
     * replacement is only real if the tests exercise it. A test suite that kept injecting a static
     * token would keep passing while the request path stopped accepting anything the server
     * actually mints — which is the one failure this whole seam exists to make impossible.
     *
     * <p>Per test method rather than per class, because the suite truncates the token tables between
     * tests: a token minted in one method would be a row that no longer exists in the next, and the
     * symptom would be a puzzling 401 in whichever test happened to run second.
     */
    protected String token() {
        return token(Scopes.SKILLS_READ, Scopes.SKILLS_WRITE);
    }

    /**
     * A token carrying exactly these scopes.
     *
     * <p>Narrowing is a request for fewer scopes, which the server honours because they are a subset
     * of what the client may ask for — so a test about the 403 challenge can have a token that is
     * missing one without needing a server configured differently from every other test's.
     */
    protected String token(String... scopes) {
        String requested = String.join(" ", scopes);
        if (!requested.equals(mintedScopes)) {
            mintedToken = mintToken(requested);
            mintedScopes = requested;
        }
        return mintedToken;
    }

    private String mintToken(String scopes) {
        seedUnattendedClient();

        // HTTP Basic, not the secret as a form field. The registered method is
        // CLIENT_SECRET_BASIC — `ClientRegistry` chooses it whenever a client has a secret — and the
        // framework refuses a request that presents its credentials any other way. A form-encoded
        // secret is `client_secret_post`, a different registered method, and the answer is
        // `invalid_client`, which reads like a wrong secret rather than a wrong place for it.
        String credentials = Base64.getEncoder().encodeToString(
                (UNATTENDED_CLIENT_ID + ":" + UNATTENDED_CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/oauth/token"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=client_credentials&scope="
                                + URLEncoder.encode(scopes, StandardCharsets.UTF_8)))
                .build());

        // A failure here is a wiring failure, not a test failure: every assertion after it would
        // report "401" and send somebody looking at the endpoint under test.
        if (response.statusCode() != 200) {
            throw new AssertionError(
                    "could not mint a token for the suite: " + response.statusCode() + " "
                            + response.body());
        }
        JsonNode token = JSON.readTree(response.body()).get("access_token");
        if (token == null || token.asText().isBlank()) {
            throw new AssertionError("the token endpoint answered without an access token: "
                    + response.body());
        }
        return token.asText();
    }

    private void seedUnattendedClient() {
        jdbc.sql("INSERT INTO oauth_client (client_id, name, registration, redirect_uris,"
                        + " grant_types, client_secret_hash, metadata, created_at, user_id)"
                        + " VALUES (:id, 'SkillMaster test client', 'preregistered', '[]',"
                        + " '[\"client_credentials\"]', :secret, :metadata,"
                        + " '2026-01-01T00:00:00Z', :userId)"
                        // Idempotent because `oauth_client` is not truncated between tests — it holds
                        // deployment facts, not fixtures — so this runs again for every method.
                        + " ON CONFLICT (client_id) DO UPDATE SET"
                        + " client_secret_hash = EXCLUDED.client_secret_hash,"
                        + " user_id = EXCLUDED.user_id")
                .param("id", UNATTENDED_CLIENT_ID)
                .param("secret", UNATTENDED_CLIENT_SECRET_HASH)
                .param("metadata", "{\"scopes\":[\"" + Scopes.SKILLS_READ + "\",\""
                        + Scopes.SKILLS_WRITE + "\"]}")
                .param("userId", SUBJECT_USER_ID)
                .update();
    }

    protected URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** @param bearerToken null to send no Authorization header at all */
    protected HttpRequest.Builder request(String path, String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        if (bearerToken != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        return builder;
    }

    protected HttpResponse<String> get(String path, String bearerToken) {
        return send(request(path, bearerToken).GET().build());
    }

    /**
     * A GET whose body is read as bytes.
     *
     * <p>Needed wherever the assertion is about content rather than about JSON: §4.2's L2 and L3
     * promise the original bytes, and decoding them to a string on the way in would silently
     * normalise exactly the thing under test — a BOM, a CRLF, an invalid sequence.
     */
    protected HttpResponse<byte[]> getBytes(String path) {
        return getBytes(path, token());
    }

    protected HttpResponse<byte[]> getBytes(String path, String bearerToken) {
        return sendBytes(request(path, bearerToken).GET().build());
    }

    protected static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("request to " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during " + request.uri(), e);
        }
    }

    protected static String wwwAuthenticate(HttpResponse<?> response) {
        return response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse(null);
    }

    protected static HttpResponse<byte[]> sendBytes(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("request to " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during " + request.uri(), e);
        }
    }
}
