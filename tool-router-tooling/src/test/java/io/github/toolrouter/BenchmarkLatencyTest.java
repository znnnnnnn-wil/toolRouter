package io.github.toolrouter;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BenchmarkLatencyTest {
    @Test void onlineQueriesBypassTheCatalogCache() {
        AtomicInteger catalogCalls = new AtomicInteger();
        AtomicInteger remoteCalls = new AtomicInteger();
        EmbeddingProvider catalogCache = texts -> {
            catalogCalls.incrementAndGet();
            return texts.stream().map(text -> new float[]{1, 0}).toList();
        };
        EmbeddingProvider remote = texts -> {
            remoteCalls.incrementAndGet();
            return texts.stream().map(text -> new float[]{1, 0}).toList();
        };
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new ToolDefinition("tool", "description", List.of(), null));
        VectorToolRouter router = new VectorToolRouter(registry,
            Benchmark.onlineProvider(catalogCache, remote));

        router.route("same query", 1);
        router.route("same query", 1);
        assertEquals(1, catalogCalls.get(), "tool vectors should be built once");
        assertEquals(2, remoteCalls.get(), "every online query must call the remote provider");
    }

    @Test void warmupIsExcludedAndPercentilesUseNearestRank() {
        AtomicInteger calls = new AtomicInteger();
        ToolRouter router = (query, topK) -> {
            calls.incrementAndGet();
            return List.of();
        };
        List<Benchmark.Case> sample = List.of(
            new Benchmark.Case("one", "tool", "test", "easy"),
            new Benchmark.Case("two", "tool", "test", "hard"));
        Benchmark.Latency latency = Benchmark.timeRoutes(router, sample, 3, 2);
        assertEquals(7, calls.get());
        assertTrue(latency.p50() >= 0);
        assertTrue(latency.p95() >= latency.p50());
        assertEquals(2.0, Benchmark.percentileMillis(
            List.of(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L), 0.50));
        assertEquals(4.0, Benchmark.percentileMillis(
            List.of(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L), 0.95));
    }
}
