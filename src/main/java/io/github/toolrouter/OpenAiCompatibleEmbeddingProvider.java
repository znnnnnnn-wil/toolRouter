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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Batch embedding client for OpenAI-compatible /embeddings endpoints. */
public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleEmbeddingProvider(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public OpenAiCompatibleEmbeddingProvider(String baseUrl, String apiKey, String model, HttpClient client) {
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) throw new IllegalArgumentException("baseUrl and model are required");
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/embeddings");
        this.apiKey = apiKey;
        this.model = model;
        this.client = client;
    }

    @Override public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        try {
            String body = mapper.writeValueAsString(Map.of("model", model, "input", texts));
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Embedding HTTP " + response.statusCode());
            JsonNode data = mapper.readTree(response.body()).path("data");
            if (!data.isArray() || data.size() != texts.size()) throw new IllegalStateException("Invalid embedding response");
            float[][] vectors = new float[texts.size()][];
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                JsonNode values = item.path("embedding");
                if (index < 0 || index >= vectors.length || vectors[index] != null || !values.isArray()) throw new IllegalStateException("Invalid embedding index");
                float[] vector = new float[values.size()];
                for (int i = 0; i < vector.length; i++) vector[i] = (float) values.get(i).asDouble();
                vectors[index] = vector;
            }
            if (Arrays.stream(vectors).anyMatch(v -> v == null)) throw new IllegalStateException("Missing embedding");
            return List.copyOf(Arrays.asList(vectors));
        } catch (IOException e) { throw new IllegalStateException("Embedding request failed", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Embedding interrupted", e); }
    }
}
