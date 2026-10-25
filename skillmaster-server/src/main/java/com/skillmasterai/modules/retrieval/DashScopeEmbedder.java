package com.skillmasterai.modules.retrieval;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Aliyun Bailian's {@code text-embedding-v4}, over its OpenAI-compatible endpoint.
 *
 * <p><strong>The provider's batch limit is ten.</strong> That was measured the hard way during the
 * v19 spike: one request with 33 inputs answers 400, one with 10 answers 200. Chunking lives here
 * rather than at the call sites because every caller would have to remember it.
 *
 * <p><strong>No retries.</strong> A timeout or a 5xx becomes {@link EmbeddingUnavailable} at once.
 * On the write path it rolls the publish back (the transaction the vector is computed in), and on
 * the read path it degrades to keyword search. A retry loop here would hold an HTTP connection
 * while the caller's own deadline is already running out.
 *
 * <p>The key comes from configuration and is never logged. Request bodies are not logged either:
 * they are the user's own descriptions.
 */
public final class DashScopeEmbedder implements Embedder {

    private static final int BATCH = 10;
    private static final URI ENDPOINT =
            URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final Duration timeout;

    public DashScopeEmbedder(String apiKey, String model, int dimensions, Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "an embedding API key is required when semantic retrieval is enabled");
        }
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.timeout = timeout;
    }

    @Override
    public String model() {
        return "dashscope:" + model + ":" + dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH) {
            out.addAll(batch(texts.subList(start, Math.min(start + BATCH, texts.size()))));
        }
        return out;
    }

    private List<float[]> batch(List<String> texts) {
        String body = JSON.writeValueAsString(java.util.Map.of(
                "model", model, "input", texts, "dimensions", dimensions));
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new EmbeddingUnavailable("the embedding service could not be reached", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingUnavailable("the embedding call was interrupted", e);
        }
        if (response.statusCode() != 200) {
            // The body is not included: it cannot contain anything but the request's own text, and
            // that text is the user's. The status is enough to decide "retry later".
            throw new EmbeddingUnavailable("the embedding service answered " + response.statusCode(),
                    null);
        }
        // **Everything below is "the service said 200 and meant nothing".** A proxy's HTML page, a
        // truncated body, a missing `data`, a missing `embedding`, and an empty `data` are all
        // failures of the same kind, and every one of them must leave as EmbeddingUnavailable: the
        // publish transaction rolls back on that type and answers 503, whereas an NPE or a Jackson
        // error would surface as 500 and describe a provider problem as a bug of ours.
        //
        // The number check inside the loop is not decoration either: `asDouble()` on a non-numeric
        // node yields 0.0, so a structurally wrong payload would otherwise be stored as an all-zero
        // vector — which pgvector's cosine distance answers NaN for, quietly removing the skill from
        // every result while the publish reports success.
        try {
            JsonNode data = JSON.readTree(response.body()).get("data");
            if (data == null || !data.isArray() || data.size() != texts.size()) {
                throw new EmbeddingUnavailable("the embedding service returned no usable vectors",
                        null);
            }
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (JsonNode item : data) {
                JsonNode embedding = item.get("embedding");
                if (embedding == null || !embedding.isArray()
                        || embedding.size() != dimensions) {
                    throw new EmbeddingUnavailable(
                            "the embedding service returned a malformed vector", null);
                }
                float[] vector = new float[dimensions];
                for (int i = 0; i < dimensions; i++) {
                    JsonNode value = embedding.get(i);
                    if (value == null || !value.isNumber()) {
                        throw new EmbeddingUnavailable(
                                "the embedding service returned a non-numeric component", null);
                    }
                    vector[i] = (float) value.asDouble();
                }
                vectors.add(vector);
            }
            return vectors;
        } catch (EmbeddingUnavailable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EmbeddingUnavailable("the embedding service answered nonsense", e);
        }
    }
}
