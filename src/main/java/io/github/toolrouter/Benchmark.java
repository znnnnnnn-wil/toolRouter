package io.github.toolrouter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reproducible benchmark over checked-in, labeled natural-language queries. */
public final class Benchmark {
    record Case(String query, String expectedTool, String category, String difficulty) {}
    private record Dataset(List<ToolDefinition> tools, List<Case> queries) {}
    record Metrics(double r1, double r3, double r5, double mrr, double p50, double p95) {}

    private static Dataset load(Path path) throws IOException {
        var root = new ObjectMapper().readTree(Files.readString(path));
        List<ToolDefinition> tools = new ArrayList<>();
        List<Case> queries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (var item : root.path("tools")) {
            List<String> tags = new ArrayList<>();
            item.path("tags").forEach(tag -> tags.add(tag.asText()));
            ToolDefinition tool = new ToolDefinition(item.path("name").asText(),
                item.path("description").asText(), tags, item.path("inputSchema"));
            if (!names.add(tool.name())) throw new IllegalArgumentException("Duplicate dataset tool");
            tools.add(tool);
        }
        for (var item : root.path("queries")) {
            Case test = new Case(item.path("query").asText(), item.path("expectedTool").asText(),
                item.path("category").asText(), item.path("difficulty").asText());
            if (test.query().isBlank() || !names.contains(test.expectedTool())
                || test.category().isBlank() || test.difficulty().isBlank()) {
                throw new IllegalArgumentException("Invalid benchmark query");
            }
            queries.add(test);
        }
        return new Dataset(List.copyOf(tools), List.copyOf(queries));
    }

    static Metrics measure(ToolRouter router, List<Case> queries) {
        if (queries.isEmpty()) throw new IllegalArgumentException("queries must not be empty");
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
            times.get((int) Math.ceil(0.50 * n) - 1) / 1e6,
            times.get((int) Math.ceil(0.95 * n) - 1) / 1e6);
    }

    private static void print(String name, Metrics m) {
        System.out.printf(Locale.ROOT, "%-10s %6.3f %6.3f %6.3f %6.3f %9.3f %9.3f%n",
            name, m.r1(), m.r3(), m.r5(), m.mrr(), m.p50(), m.p95());
    }

    public static void main(String[] args) throws Exception {
        Dataset data = load(Path.of("benchmark/dataset.json"));
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        data.tools().forEach(registry::register);
        List<Case> hard = data.queries().stream().filter(c -> c.difficulty().equals("hard")).toList();
        System.out.printf("Tool Router Benchmark%nTools: %d  Queries: %d  Hard: %d%n",
            data.tools().size(), data.queries().size(), hard.size());
        System.out.println("Top-5 retrieval, hot tool/query vectors; latency excludes embedding API calls");
        System.out.println("Router        R@1    R@3    R@5  MRR@5   P50(ms)   P95(ms)");
        String key = System.getenv("DASHSCOPE_API_KEY");
        String baseUrl = env("EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String model = env("EMBEDDING_MODEL", "text-embedding-v4");
        int dimensions = Integer.parseInt(env("EMBEDDING_DIMENSIONS", "1024"));
        try (BM25ToolRouter bm25 = new BM25ToolRouter(registry)) {
            print("BM25", measure(bm25, data.queries()));
            if (key == null || key.isBlank()) {
                System.out.println("Vector     Not measured (DASHSCOPE_API_KEY absent)");
                System.out.println("Hybrid     Not measured (DASHSCOPE_API_KEY absent)");
                if (Arrays.asList(args).contains("--scaling")) scaling(data, null);
                return;
            }
            EmbeddingProvider remote = new OpenAiCompatibleEmbeddingProvider(baseUrl, key, model, dimensions);
            BenchmarkEmbeddingCache cache = new BenchmarkEmbeddingCache(remote,
                Path.of(".benchmark-cache"), baseUrl + "\n" + model + "\n" + dimensions, dimensions);
            // This precompute is intentionally excluded from the routing latency table.
            long precomputeStart = System.nanoTime();
            cache.embed(data.tools().stream().map(new ToolTextBuilder()::build).toList());
            cache.embed(data.queries().stream().map(Case::query).toList());
            System.out.printf(Locale.ROOT, "Embedding precompute (cache-aware): %.3f s%n",
                (System.nanoTime() - precomputeStart) / 1e9);
            VectorToolRouter vector = new VectorToolRouter(registry, cache);
            HybridToolRouter hybrid = new HybridToolRouter(bm25, vector);
            print("Vector", measure(vector, data.queries()));
            print("Hybrid", measure(hybrid, data.queries()));
            System.out.println("Hard queries only (accuracy and MRR@5):");
            print("BM25", measure(bm25, hard));
            print("Vector", measure(vector, hard));
            print("Hybrid", measure(hybrid, hard));
            compareHard(hard, bm25, vector, hybrid);
            if (Arrays.asList(args).contains("--scaling")) scaling(data, cache);
        }
    }

    private static void compareHard(List<Case> hard, ToolRouter bm25, ToolRouter vector, ToolRouter hybrid) {
        int vectorOnly = 0;
        int bm25Only = 0;
        int hybridRecovery = 0;
        for (Case item : hard) {
            boolean lexical = topIs(bm25, item);
            boolean semantic = topIs(vector, item);
            boolean fused = topIs(hybrid, item);
            if (semantic && !fused) vectorOnly++;
            if (lexical && !fused) bm25Only++;
            if (fused && !semantic && !lexical) hybridRecovery++;
        }
        System.out.printf("Hard Top-1: vector correct but hybrid wrong=%d; BM25 correct but hybrid wrong=%d; hybrid-only recoveries=%d%n",
            vectorOnly, bm25Only, hybridRecovery);
    }

    private static boolean topIs(ToolRouter router, Case item) {
        List<RouteResult> results = router.route(item.query(), 1);
        return !results.isEmpty() && results.get(0).tool().name().equals(item.expectedTool());
    }

    private static void printLatency(String name, Metrics m) {
        System.out.printf(Locale.ROOT, "%-10s P50=%.3f ms  P95=%.3f ms%n", name, m.p50(), m.p95());
    }
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void scaling(Dataset data, EmbeddingProvider provider) {
        System.out.println("Scaling: 50 seeded query samples per size, P50/P95 ms, prebuilt indices");
        List<Case> sample = new ArrayList<>(data.queries());
        Collections.shuffle(sample, new java.util.Random(42));
        sample = sample.subList(0, 50);
        for (int size : new int[]{100, 500, 1000}) {
            InMemoryToolRegistry registry = new InMemoryToolRegistry();
            for (int i = 0; i < size; i++) {
                ToolDefinition source = data.tools().get(i % data.tools().size());
                registry.register(new ToolDefinition(source.name() + "_" + i,
                    source.description(), source.tags(), null));
            }
            try (BM25ToolRouter bm25 = new BM25ToolRouter(registry)) {
                System.out.printf("%d tools%n", size);
                printLatency("BM25", measure(bm25, sample));
                if (provider != null) {
                    VectorToolRouter vector = new VectorToolRouter(registry, provider);
                    vector.route(sample.get(0).query(), 5);
                    printLatency("Vector", measure(vector, sample));
                    printLatency("Hybrid", measure(new HybridToolRouter(bm25, vector), sample));
                }
            }
        }
    }
}
