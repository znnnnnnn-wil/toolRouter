package io.github.toolrouter;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RouterEdgeTest {
    private static ToolDefinition tool(String name, String description, String... tags) {
        return new ToolDefinition(name, description, List.of(tags), null);
    }

    @Test void schemaAndTagsAreDefensivelyCopied() {
        ObjectNode schema = new ObjectMapper().createObjectNode().put("type", "object");
        List<String> tags = new ArrayList<>(List.of("one"));
        ToolDefinition tool = new ToolDefinition("safe", null, tags, schema);
        tags.add("two");
        schema.put("type", "array");
        assertEquals(List.of("one"), tool.tags());
        assertEquals("", tool.description());
        assertEquals("object", tool.inputSchema().path("type").asText());
        ((ObjectNode) tool.inputSchema()).put("type", "changed");
        assertEquals("object", tool.inputSchema().path("type").asText());
        assertThrows(IllegalArgumentException.class, () -> new ToolDefinition("", "", List.of(), null));
    }

    @Test void scoreGapHintIsNotProbability() {
        ToolDefinition a = tool("a", "first");
        ToolDefinition b = tool("b", "second");
        ToolRouter uncertain = (q, k) -> List.of(new RouteResult(a, 10, 1), new RouteResult(b, 9.5, 2));
        RouteResponse hint = uncertain.routeWithHint("x", 2);
        assertEquals(0.05, hint.scoreGapHint(), 1e-9);
        assertTrue(hint.fallbackRecommended());
        AtomicInteger requestedDepth = new AtomicInteger();
        ToolRouter separated = (q, k) -> {
            requestedDepth.set(k);
            return List.of(new RouteResult(a, 10, 1), new RouteResult(b, 2, 2))
                .subList(0, Math.min(k, 2));
        };
        assertFalse(separated.routeWithHint("x", 2).fallbackRecommended());
        RouteResponse topOne = separated.routeWithHint("x", 1);
        assertEquals(2, requestedDepth.get());
        assertEquals(1, topOne.candidates().size());
        assertEquals(0.8, topOne.scoreGapHint(), 1e-9);
        assertFalse(topOne.fallbackRecommended());
        assertTrue(separated.routeWithHint("x", 0).candidates().isEmpty());
        ToolRouter one = (q, k) -> List.of(new RouteResult(a, 10, 1));
        assertTrue(one.routeWithHint("x", 2).fallbackRecommended());
        ToolRouter none = (q, k) -> List.of();
        assertTrue(none.routeWithHint("x", 2).fallbackRecommended());
    }

    @Test void vectorTopKAndInvalidInputs() {
        EmbeddingProvider provider = texts -> texts.stream().map(t -> new float[]{
            t.contains("alpha") ? 1 : 0, t.contains("beta") ? 1 : 0}).toList();
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(tool("alpha", "alpha"));
        registry.register(tool("beta", "beta"));
        registry.register(tool("alpha_beta", "alpha beta"));
        VectorToolRouter router = new VectorToolRouter(registry, provider);
        assertEquals(2, router.route("alpha", 2).size());
        assertEquals("alpha", router.route("alpha", 1).get(0).tool().name());
        assertEquals(3, router.route("alpha", 99).size());
        assertTrue(router.route("alpha", 0).isEmpty());
        assertTrue(router.route("", 5).isEmpty());
        assertThrows(IllegalArgumentException.class,
            () -> VectorToolRouter.cosine(new float[]{Float.NaN}, new float[]{1}));
    }

    @Test void compatibleProviderBatchesAndRestoresResponseOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                var request = mapper.readTree(exchange.getRequestBody());
                var inputs = request.path("input");
                assertTrue(inputs.size() <= 10);
                assertEquals("text-embedding-v4", request.path("model").asText());
                assertEquals(3, request.path("dimensions").asInt());
                assertEquals("float", request.path("encoding_format").asText());
                calls.incrementAndGet();
                var data = mapper.createArrayNode();
                for (int i = inputs.size() - 1; i >= 0; i--) {
                    var row = mapper.createObjectNode();
                    row.put("index", i);
                    var embedding = row.putArray("embedding");
                    embedding.add(inputs.get(i).asText().length()).add(1).add(0);
                    data.add(row);
                }
                byte[] response = mapper.createObjectNode().set("data", data).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var provider = new OpenAiCompatibleEmbeddingProvider(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", null,
                "text-embedding-v4", 3, 10, HttpClient.newHttpClient());
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < 23; i++) texts.add("text " + i);
            List<float[]> vectors = provider.embed(texts);
            assertEquals(23, vectors.size());
            assertEquals(3, calls.get());
            assertEquals(texts.get(10).length(), vectors.get(10)[0]);
        } finally {
            server.stop(0);
        }
    }
}
