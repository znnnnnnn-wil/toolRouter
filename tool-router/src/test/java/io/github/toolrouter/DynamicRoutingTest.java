package io.github.toolrouter;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DynamicRoutingTest {
    private static ToolDefinition tool(String name, String description) {
        return new ToolDefinition(name, description, List.of(), null);
    }

    @Test void bm25SeesDynamicRegistrationAndClosesCleanly() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        BM25ToolRouter router = new BM25ToolRouter(registry);
        assertTrue(router.route("weather", 5).isEmpty());
        registry.register(tool("weather_forecast", "Predict future weather"));
        assertEquals("weather_forecast", router.route("forecast", 1).get(0).tool().name());
        router.close();
        router.close();
        assertThrows(IllegalStateException.class, () -> router.route("forecast", 1));
    }

    @Test void vectorReembedsOnlyAfterRegistryVersionChanges() {
        AtomicInteger batches = new AtomicInteger();
        EmbeddingProvider provider = texts -> {
            batches.incrementAndGet();
            return texts.stream().map(t -> new float[]{t.contains("refund") ? 1 : 0,
                t.contains("weather") ? 1 : 0}).toList();
        };
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(tool("refund", "Return money"));
        VectorToolRouter router = new VectorToolRouter(registry, provider);
        router.route("refund", 1);
        assertEquals(2, batches.get()); // one tool batch, one query batch
        router.route("refund", 1);
        assertEquals(3, batches.get()); // query is not cached by the core router
        registry.register(tool("weather", "Predict weather"));
        router.route("refund", 1);
        assertEquals(5, batches.get()); // refreshed tool batch plus query
        registry.remove("weather");
        router.route("refund", 1);
        assertEquals(7, batches.get());
    }

    @Test void concurrentVectorRouteAndUpdateRemainConsistent() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(tool("status", "alpha"));
        EmbeddingProvider provider = texts -> texts.stream().map(t -> new float[]{
            t.contains("alpha") ? 1 : 0, t.contains("beta") ? 1 : 0}).toList();
        VectorToolRouter router = new VectorToolRouter(registry, provider);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var writer = pool.submit(() -> {
                for (int i = 0; i < 40; i++) registry.update(tool("status", i % 2 == 0 ? "alpha" : "beta"));
            });
            var reader = pool.submit(() -> {
                for (int i = 0; i < 40; i++) {
                    List<RouteResult> results = router.route("alpha", 5);
                    assertEquals(1, results.size());
                    assertEquals("status", results.get(0).tool().name());
                }
            });
            writer.get();
            reader.get();
        } finally {
            pool.shutdown();
        }
    }

    @Test void registryRejectsUnknownUpdateAndPreservesSnapshot() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.update(tool("missing", "x")));
        registry.register(tool("one", "alpha"));
        InMemoryToolRegistry.Snapshot before = registry.snapshot();
        registry.update(tool("one", "beta"));
        assertEquals("alpha", before.tools().get("one").description());
        assertEquals("beta", registry.get("one").orElseThrow().description());
        assertThrows(UnsupportedOperationException.class,
            () -> before.tools().put("two", tool("two", "x")));
    }
}
