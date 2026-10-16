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

    /** For reading the answers a test has to look inside: the token endpoint's, and registration's. */
    protected static final JsonMapper JSON = JsonMapper.builder().build();

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
