package io.github.toolrouter;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenchmarkMetricsTest {
    private static ToolDefinition tool(String name, String description) {
        return new ToolDefinition(name, description, List.of(), null);
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
}
