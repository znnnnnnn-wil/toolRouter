package io.github.toolrouter.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
    private final Duration requestTimeout;
    private final int maxRetries;
    private final Duration initialBackoff;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleEmbeddingProvider(String baseUrl, String apiKey, String model, int dimensions) {
        this(baseUrl, apiKey, model, dimensions, 10, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public OpenAiCompatibleEmbeddingProvider(String baseUrl, String apiKey, String model, int dimensions,
                                             int batchSize, HttpClient client) {
        this(baseUrl, apiKey, model, dimensions, batchSize, client, Duration.ofSeconds(60), 2,
            Duration.ofMillis(200));
    }

    public OpenAiCompatibleEmbeddingProvider(String baseUrl, String apiKey, String model, int dimensions,
                                             int batchSize, HttpClient client, Duration requestTimeout,
                                             int maxRetries, Duration initialBackoff) {
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()
                || dimensions <= 0 || batchSize <= 0 || requestTimeout == null
                || requestTimeout.isZero() || requestTimeout.isNegative() || initialBackoff == null
                || initialBackoff.isNegative() || maxRetries < 0 || maxRetries > 5) {
            throw new IllegalArgumentException("Invalid embedding provider configuration");
        }
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/embeddings");
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.batchSize = batchSize;
        this.client = java.util.Objects.requireNonNull(client);
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff;
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
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(requestTimeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
            HttpRequest built = request.build();
            HttpResponse<String> response = client.send(built, HttpResponse.BodyHandlers.ofString());
            for (int attempt = 0; attempt < maxRetries && retryable(response.statusCode()); attempt++) {
                Thread.sleep(retryDelayMillis(response, attempt));
                response = client.send(built, HttpResponse.BodyHandlers.ofString());
            }
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

    private static boolean retryable(int status) {
        return status == 408 || status == 429 || status == 500 || status == 502
            || status == 503 || status == 504;
    }

    private long retryDelayMillis(HttpResponse<?> response, int attempt) {
        long base = initialBackoff.compareTo(Duration.ofSeconds(5)) >= 0
            ? 5_000 : initialBackoff.toMillis();
        long fallback = Math.min(5_000, base * (1L << attempt));
        String header = response.headers().firstValue("Retry-After").orElse(null);
        if (header == null) return fallback;
        try {
            return Math.min(5_000, Math.max(0, Math.multiplyExact(Long.parseLong(header.trim()), 1_000)));
        } catch (NumberFormatException | ArithmeticException ignored) {
            try {
                Instant date = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.min(5_000, Math.max(0, Duration.between(Instant.now(), date).toMillis()));
            } catch (DateTimeParseException ignoredDate) {
                return fallback;
            }
        }
    }
}
