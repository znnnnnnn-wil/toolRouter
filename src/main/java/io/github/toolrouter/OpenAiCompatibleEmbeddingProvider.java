package io.github.toolrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Batch client for an OpenAI-compatible embeddings endpoint. */
public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final int batchSize;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleEmbeddingProvider(String baseUrl, String apiKey, String model, int dimensions) {
        this(baseUrl, apiKey, model, dimensions, 10, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public OpenAiCompatibleEmbeddingProvider(String baseUrl, String apiKey, String model, int dimensions,
                                             int batchSize, HttpClient client) {
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()
                || dimensions <= 0 || batchSize <= 0) {
            throw new IllegalArgumentException("baseUrl, model, positive dimensions and batchSize are required");
        }
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/embeddings");
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.batchSize = batchSize;
        this.client = client;
    }

    @Override public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        List<float[]> all = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            all.addAll(request(texts.subList(start, Math.min(start + batchSize, texts.size()))));
        }
        return List.copyOf(all);
    }

    private List<float[]> request(List<String> texts) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                "model", model, "input", texts, "dimensions", dimensions, "encoding_format", "float"));
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Embedding HTTP " + response.statusCode());
            }
            JsonNode data = mapper.readTree(response.body()).path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                throw new IllegalStateException("Invalid embedding response count");
            }
            float[][] vectors = new float[texts.size()][];
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                JsonNode values = item.path("embedding");
                if (index < 0 || index >= vectors.length || vectors[index] != null
                        || !values.isArray() || values.size() != dimensions) {
                    throw new IllegalStateException("Invalid embedding index or dimension");
                }
                float[] vector = new float[dimensions];
                for (int i = 0; i < dimensions; i++) {
                    if (!values.get(i).isNumber()) throw new IllegalStateException("Invalid embedding value");
                    vector[i] = (float) values.get(i).asDouble();
                    if (!Float.isFinite(vector[i])) throw new IllegalStateException("Non-finite embedding value");
                }
                vectors[index] = vector;
            }
            List<float[]> ordered = new ArrayList<>(vectors.length);
            for (float[] vector : vectors) {
                if (vector == null) throw new IllegalStateException("Missing embedding");
                ordered.add(vector);
            }
            return ordered;
        } catch (IOException e) {
            throw new IllegalStateException("Embedding request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding interrupted", e);
        }
    }
}
