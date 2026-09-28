package io.github.toolrouter.embedding;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EmbeddingProviderRetryTest {
    @Test void retriesTransientResponsesAndPreservesResult() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                int attempt = requests.incrementAndGet();
                int status = attempt == 1 ? 429 : attempt == 2 ? 503 : 200;
                byte[] body = (status == 200
                    ? "{\"data\":[{\"index\":0,\"embedding\":[1.0,2.0]}]}"
                    : "{}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var provider = provider(server, 2);
            assertArrayEquals(new float[]{1, 2}, provider.embed("query"));
            assertEquals(3, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test void doesNotRetryAuthenticationErrors() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider(server, 2).embed(List.of("query")));
            assertEquals("Embedding HTTP 401", error.getMessage());
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static OpenAiCompatibleEmbeddingProvider provider(HttpServer server, int retries) {
        return new OpenAiCompatibleEmbeddingProvider(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            null, "test-model", 2, 10, HttpClient.newHttpClient(),
            Duration.ofSeconds(2), retries, Duration.ZERO);
    }
}
