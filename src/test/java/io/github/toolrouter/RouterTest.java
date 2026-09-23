package io.github.toolrouter;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class RouterTest {
    private static ToolDefinition tool(String name, String description) {
        return new ToolDefinition(name, description, List.of(), null);
    }
    private static final EmbeddingProvider FIXED = texts -> texts.stream().map(text -> {
        String lower = text.toLowerCase();
        return new float[]{lower.contains("refund") ? 1 : 0, lower.contains("weather") ? 1 : 0, lower.contains("order") ? 1 : 0};
    }).toList();

    @Test void registryAndText() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        ToolDefinition original = tool("get_order", "Read an order");
        registry.register(original);
        assertThrows(IllegalArgumentException.class, () -> registry.register(original));
        assertEquals(1, registry.size());
        registry.update(tool("get_order", "Read a purchase"));
        assertEquals("Read a purchase", registry.get("get_order").orElseThrow().description());
        assertTrue(new ToolTextBuilder().build(original).contains("Description: Read an order"));
        assertTrue(registry.remove("get_order"));
        assertFalse(registry.remove("get_order"));
        assertTrue(registry.list().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> tool(" ", ""));
    }

    @Test void concurrentRegistryWriters() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        var pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int thread = t;
            jobs.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 25; i++) registry.register(tool("tool_" + thread + "_" + i, "test"));
                return null;
            }));
        }
        start.countDown();
        for (var job : jobs) job.get();
        pool.shutdown();
        assertEquals(100, registry.size());
    }

    @Test void bm25RebuildAndEdges() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(tool("refund_order", "Return money for an order"));
        registry.register(tool("cancel_order", "Stop an order before shipping"));
        try (BM25ToolRouter router = new BM25ToolRouter(registry)) {
            assertEquals("refund_order", router.route("refund order", 1).get(0).tool().name());
            assertTrue(router.route("", 5).isEmpty());
            assertTrue(router.route("refund", 0).isEmpty());
            assertTrue(router.route("refund", -1).isEmpty());
            assertTrue(router.route("refund", 50).size() <= 2);
            registry.update(tool("refund_order", "Weather forecast only"));
            assertEquals("cancel_order", router.route("shipping", 1).get(0).tool().name());
            registry.remove("cancel_order");
            assertTrue(router.route("shipping", 5).isEmpty());
        }
    }

    @Test void vectorCosineAndUpdates() {
        assertEquals(1, VectorToolRouter.cosine(new float[]{1, 0}, new float[]{2, 0}), 1e-9);
        assertEquals(0, VectorToolRouter.cosine(new float[]{0, 0}, new float[]{1, 0}));
        assertThrows(IllegalArgumentException.class, () -> VectorToolRouter.cosine(new float[]{1}, new float[]{1, 2}));
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        VectorToolRouter router = new VectorToolRouter(registry, FIXED);
        assertTrue(router.route("refund", 3).isEmpty());
        registry.register(tool("refund_order", "refund order"));
        registry.register(tool("weather_forecast", "weather tomorrow"));
        assertEquals("refund_order", router.route("refund", 1).get(0).tool().name());
        double oldScore = router.route("weather", 5).stream().filter(r -> r.tool().name().equals("refund_order")).findFirst().orElseThrow().score();
        registry.update(tool("refund_order", "weather tomorrow"));
        double newScore = router.route("weather", 5).stream().filter(r -> r.tool().name().equals("refund_order")).findFirst().orElseThrow().score();
        assertTrue(newScore > oldScore);
        registry.remove("refund_order");
        assertEquals(1, router.route("weather", 5).size());
    }

    @Test void rrfMergesDuplicatesAndOrders() {
        ToolDefinition a = tool("a", "");
        ToolDefinition b = tool("b", "");
        ToolDefinition c = tool("c", "");
        ToolRouter first = (q, k) -> List.of(new RouteResult(a, 20, 1), new RouteResult(b, 10, 2));
        ToolRouter second = (q, k) -> List.of(new RouteResult(b, 0.8, 1), new RouteResult(a, 0.5, 2), new RouteResult(c, 0.1, 3));
        HybridToolRouter hybrid = new HybridToolRouter(first, second, 60, 3);
        assertEquals(List.of("a", "b", "c"), hybrid.route("x", 5).stream().map(r -> r.tool().name()).toList());
        assertEquals(1.0 / 61 + 1.0 / 62, hybrid.route("x", 1).get(0).score(), 1e-9);
        assertTrue(hybrid.route(" ", 5).isEmpty());
    }

    @Test void benchmarkMetricsCountRanks() {
        ToolDefinition a = tool("a", "");
        ToolDefinition b = tool("b", "");
        ToolRouter router = (q, k) -> List.of(new RouteResult(a, 1, 1), new RouteResult(b, 0.5, 2));
        Benchmark.Metrics metrics = Benchmark.measure(router, List.of(
            new Benchmark.Case("first", "a", "test", "easy"),
            new Benchmark.Case("second", "b", "test", "hard")));
        assertEquals(0.5, metrics.r1());
        assertEquals(1, metrics.r3());
        assertEquals(1, metrics.r5());
        assertEquals(0.75, metrics.mrr());
    }

    @Test void concurrentBm25RoutingAndUpdates() throws Exception {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(tool("status", "version alpha"));
        try (BM25ToolRouter router = new BM25ToolRouter(registry)) {
            var pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            var writer = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 50; i++) registry.update(tool("status", "version " + (i % 2 == 0 ? "alpha" : "beta")));
                return null;
            });
            var reader = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 50; i++) {
                    for (RouteResult result : router.route("version", 5)) assertEquals("status", result.tool().name());
                }
                return null;
            });
            start.countDown();
            writer.get();
            reader.get();
            pool.shutdown();
        }
    }
}
