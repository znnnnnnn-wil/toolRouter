package io.github.toolrouter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Reproducible CLI benchmark over checked-in human-written examples. */
public final class Benchmark {
    record Case(String query, String expectedTool, String category, String difficulty) {}
    private record Dataset(List<ToolDefinition> tools, List<Case> queries) {}
    record Metrics(double r1, double r3, double r5, double mrr, double p50, double p95) {}

    private static Dataset load(Path path) throws IOException {
        var root = new ObjectMapper().readTree(Files.readString(path));
        List<ToolDefinition> tools = new ArrayList<>();
        List<Case> queries = new ArrayList<>();
        for (var item : root.path("tools")) {
            List<String> tags = new ArrayList<>();
            item.path("tags").forEach(tag -> tags.add(tag.asText()));
            tools.add(new ToolDefinition(item.path("name").asText(), item.path("description").asText(), tags, item.path("inputSchema")));
        }
        for (var item : root.path("queries")) queries.add(new Case(item.path("query").asText(), item.path("expectedTool").asText(), item.path("category").asText(), item.path("difficulty").asText()));
        return new Dataset(tools, queries);
    }

    static Metrics measure(ToolRouter router, List<Case> queries) {
        for (Case item : queries.subList(0, Math.min(20, queries.size()))) router.route(item.query(), 5);
        int r1 = 0, r3 = 0, r5 = 0;
        double reciprocal = 0;
        List<Long> times = new ArrayList<>();
        for (Case item : queries) {
            long start = System.nanoTime();
            List<RouteResult> results = router.route(item.query(), 5);
            times.add(System.nanoTime() - start);
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).tool().name().equals(item.expectedTool())) {
                    if (i == 0) r1++;
                    if (i < 3) r3++;
                    r5++;
                    reciprocal += 1.0 / (i + 1);
                    break;
                }
            }
        }
        Collections.sort(times);
        int n = queries.size();
        return new Metrics((double) r1 / n, (double) r3 / n, (double) r5 / n, reciprocal / n,
            times.get((int) Math.ceil(0.50 * n) - 1) / 1e6, times.get((int) Math.ceil(0.95 * n) - 1) / 1e6);
    }

    private static void print(String name, Metrics m) {
        System.out.printf(Locale.ROOT, "%-10s %6.3f %6.3f %6.3f %6.3f %9.3f %9.3f%n",
            name, m.r1(), m.r3(), m.r5(), m.mrr(), m.p50(), m.p95());
    }

    public static void main(String[] args) throws Exception {
        Dataset data = load(Path.of("benchmark/dataset.json"));
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        data.tools().forEach(registry::register);
        System.out.printf("Tool Router Benchmark%nTools: %d  Queries: %d%n", data.tools().size(), data.queries().size());
        System.out.println("Router        R@1    R@3    R@5    MRR   P50(ms)   P95(ms)");
        try (BM25ToolRouter bm25 = new BM25ToolRouter(registry)) {
            print("BM25", measure(bm25, data.queries()));
            String url = System.getenv("EMBEDDING_BASE_URL");
            String model = System.getenv("EMBEDDING_MODEL");
            if (url != null && model != null) {
                EmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(url, System.getenv("EMBEDDING_API_KEY"), model);
                VectorToolRouter vector = new VectorToolRouter(registry, provider);
                print("Vector", measure(vector, data.queries()));
                print("Hybrid", measure(new HybridToolRouter(bm25, vector), data.queries()));
            } else {
                System.out.println("Vector     Not measured (set EMBEDDING_BASE_URL and EMBEDDING_MODEL)");
                System.out.println("Hybrid     Not measured (set EMBEDDING_BASE_URL and EMBEDDING_MODEL)");
            }
        }
        if (Arrays.asList(args).contains("--scaling")) scaling(data);
    }

    private static void scaling(Dataset data) {
        System.out.println("Scaling: BM25, 50 query samples per size (P50/P95 ms)");
        for (int size : new int[]{50, 100, 500, 1000}) {
            InMemoryToolRegistry registry = new InMemoryToolRegistry();
            for (int i = 0; i < size; i++) {
                ToolDefinition source = data.tools().get(i % data.tools().size());
                registry.register(new ToolDefinition(source.name() + "_" + i, source.description(), source.tags(), null));
            }
            try (BM25ToolRouter router = new BM25ToolRouter(registry)) {
                Metrics m = measure(router, data.queries().subList(0, 50));
                System.out.printf(Locale.ROOT, "%4d tools: %.3f / %.3f%n", size, m.p50(), m.p95());
            }
        }
    }
}
