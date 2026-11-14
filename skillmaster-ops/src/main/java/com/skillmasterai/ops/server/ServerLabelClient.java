package com.skillmasterai.ops.server;

import com.skillmasterai.ops.config.OpsProperties;
import com.skillmasterai.ops.ports.LabelSource;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The label plane's adapter: three calls on the server's {@code /inner/v1}, no retry.
 *
 * <p><strong>Why no retry.</strong> A refresh walks every public skill and records each result, so
 * the next run is the retry. One skill failing must not hold the run open, and every request is
 * idempotent at the server (the list is paged, the content is a read, the replace is a set).
 */
@Component
public class ServerLabelClient implements LabelSource {

    private final HttpClient http;
    private final ObjectMapper json;
    private final OpsProperties properties;

    public ServerLabelClient(ObjectMapper json, OpsProperties properties) {
        this.json = json;
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public Page publicSkills(String cursor, int limit) {
        StringBuilder path = new StringBuilder(trimSlash(properties.serverUrl()))
                .append("/inner/v1/skills?limit=").append(limit);
        if (cursor != null && !cursor.isBlank()) {
            path.append("&cursor=").append(encode(cursor));
        }
        JsonNode root = get(path.toString());
        List<SkillRef> items = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            items.add(new SkillRef(item.path("skill_id").asText(""),
                    item.path("namespace").asText(""), item.path("name").asText(""),
                    item.path("current_version_id").asText("")));
        }
        String next = root.path("next_cursor").isNull() ? null
                : root.path("next_cursor").asText("");
        return new Page(items, next == null || next.isBlank() ? null : next);
    }

    @Override
    public Optional<Content> content(String namespace, String name) {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(properties.serverUrl()) + "/inner/v1/skills/"
                        + encode(namespace) + "/" + encode(name) + "/content"))
                .timeout(Duration.ofSeconds(15))
                .header("X-Inner-Token", properties.innerToken())
                .GET());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireOk(response);
        JsonNode root = json.readTree(response.body());
        List<FileFact> files = new ArrayList<>();
        for (JsonNode file : root.path("files")) {
            files.add(new FileFact(file.path("relpath").asText(""), file.path("size").asLong(0),
                    file.path("is_binary").asBoolean(false)));
        }
        return Optional.of(new Content(root.path("skill_id").asText(""),
                root.path("version_id").asText(""), root.path("skill_md").asText(""), files));
    }

    @Override
    public boolean replaceLabels(String namespace, String name, String versionId,
            List<String> definitionIds) {
        List<Map<String, Object>> claims = definitionIds.stream()
                .map(id -> Map.<String, Object>of("definition_id", id))
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version_id", versionId);
        payload.put("claims", claims);
        byte[] body = json.writeValueAsBytes(payload);
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(properties.serverUrl()) + "/inner/v1/skills/"
                        + encode(namespace) + "/" + encode(name) + "/labels"))
                // Shorter than the submission's timeout: a label push happens once per import
                // entry, and a slow server must not multiply the import's wall clock.
                .timeout(Duration.ofSeconds(5))
                .header("X-Inner-Token", properties.innerToken())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
        requireOk(response);
        return json.readTree(response.body()).path("applied").asBoolean(true);
    }

    private JsonNode get(String url) {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("X-Inner-Token", properties.innerToken())
                .GET());
        requireOk(response);
        return json.readTree(response.body());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new IllegalStateException("the server did not answer", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the request was interrupted", interrupted);
        }
    }

    private static void requireOk(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("status", response.statusCode());
            value.put("body", response.body());
            throw new IllegalStateException("the server refused the label request: " + value);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
