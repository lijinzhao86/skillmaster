package com.skillmasterai.ops.server;

import com.skillmasterai.ops.config.OpsProperties;
import com.skillmasterai.ops.imports.LicenseDecision;
import com.skillmasterai.ops.jobs.SubmissionRejected;
import com.skillmasterai.ops.ports.SubmissionPort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The submission adapter: one multipart request to the server's inner plane.
 *
 * <p>No retry. The server decides replay by {@code (binding, revision)}, so a retry would be safe —
 * but a submit that hangs twice would hold the run open for minutes, and the next run re-sends
 * anyway. The timeout is the control; the next run is the retry.
 */
@Component
public class ServerSubmissionClient implements SubmissionPort {

    private final HttpClient http;
    private final ObjectMapper json;
    private final OpsProperties properties;

    public ServerSubmissionClient(ObjectMapper json, OpsProperties properties) {
        this.json = json;
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public Result submit(Submission submission) {
        String boundary = "----ops" + UUID.randomUUID();
        byte[] body = multipart(boundary, submission);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(properties.serverUrl())
                        + "/inner/v1/sync/submissions"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Inner-Token", properties.innerToken())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new IllegalStateException("the submission did not reach the server", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the submission was interrupted", interrupted);
        }
        if (response.statusCode() != 200) {
            throw new SubmissionRejected(codeOf(response.body(), response.statusCode()));
        }
        JsonNode root = json.readTree(response.body());
        return new Result(root.path("skill_id").asString(), root.path("draft_id").asString(),
                root.path("created").asBoolean(false), root.path("replayed").asBoolean(false));
    }

    private byte[] multipart(String boundary, Submission submission) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"metadata\"\r\n");
        write(out, "Content-Type: application/json\r\n\r\n");
        write(out, json.writeValueAsString(metadata(submission)));
        write(out, "\r\n--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"archive\"; filename=\"entry.zip\"\r\n");
        write(out, "Content-Type: application/zip\r\n\r\n");
        out.writeBytes(SubmissionZip.build(submission.platformName(), submission.files()));
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private Map<String, Object> metadata(Submission submission) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("kind", submission.binding().kind());
        binding.put("locator", submission.binding().locator());
        binding.put("path", submission.binding().path());

        LicenseDecision license = submission.license();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("spdx", license.spdx());
        facts.put("name", license.name());
        facts.put("copyright", license.copyright());
        facts.put("status", license.status() == null ? "MISSING" : license.status().name());
        facts.put("text", license.text());
        facts.put("path", license.path());
        facts.put("url", license.url());
        facts.put("detail", license.detail());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("binding", binding);
        metadata.put("revision", submission.revision());
        metadata.put("platform_name", submission.platformName());
        metadata.put("license", facts);
        metadata.put("signals", submission.signalsJson());
        return metadata;
    }

    private static String codeOf(String body, int status) {
        if (body != null && body.contains("\"code\"")) {
            try {
                return body.substring(body.indexOf("\"code\"") + 6).replaceAll("^[^A-Za-z]*", "")
                        .split("\"", 2)[0];
            } catch (RuntimeException ignored) {
                // fall through to the status code
            }
        }
        return "server_" + status;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void write(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
