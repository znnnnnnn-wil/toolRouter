package io.github.toolrouter;

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
    record Latency(double p50, double p95) {}

    private static Dataset load(Path path) throws IOException {
        List<ToolDefinition> tools = new ArrayList<>();
        List<Case> queries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty() || !lines.get(0).equals("category|name|description|tags|easy|hard")) {
            throw new IllegalArgumentException("Invalid benchmark header");
        }
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\\|", -1);
            if (fields.length != 6 || Arrays.stream(fields).anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("Invalid benchmark row");
            }
            ToolDefinition tool = new ToolDefinition(fields[1], fields[2],
                List.of(fields[3].split(" ")), null);
            if (!names.add(tool.name())) throw new IllegalArgumentException("Duplicate dataset tool");
            tools.add(tool);
            queries.add(new Case(fields[4], tool.name(), fields[0], "easy"));
            queries.add(new Case(fields[5], tool.name(), fields[0], "hard"));
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
            percentileMillis(times, 0.50), percentileMillis(times, 0.95));
    }

    private static void print(String name, Metrics m) {
        System.out.printf(Locale.ROOT, "%-10s %6.3f %6.3f %6.3f %6.3f %9.3f %9.3f%n",
            name, m.r1(), m.r3(), m.r5(), m.mrr(), m.p50(), m.p95());
    }

    public static void main(String[] args) throws Exception {
        Dataset data = load(Path.of("benchmark/dataset.tsv"));
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        data.tools().forEach(registry::register);
        List<Case> hard = data.queries().stream().filter(c -> c.difficulty().equals("hard")).toList();
        System.out.printf("Tool Router Benchmark%nTools: %d  Queries: %d  Hard: %d%n",
            data.tools().size(), data.queries().size(), hard.size());
        System.out.println("Retrieval-only latency: hot tool/query vectors; query embedding API calls excluded");
        System.out.println("Router        R@1    R@3    R@5  MRR@5   P50(ms)   P95(ms)");
        String key = System.getenv("DASHSCOPE_API_KEY");
        boolean hasKey = key != null && !key.isBlank();
        boolean cachedOnly = Arrays.asList(args).contains("--cached-only");
        String baseUrl = env("EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String model = env("EMBEDDING_MODEL", "text-embedding-v4");
        int dimensions = Integer.parseInt(env("EMBEDDING_DIMENSIONS", "1024"));
        try (BM25ToolRouter bm25 = new BM25ToolRouter(registry)) {
            print("BM25", measure(bm25, data.queries()));
            if (!hasKey && !cachedOnly) {
                System.out.println("Vector     Not measured (DASHSCOPE_API_KEY absent)");
                System.out.println("Hybrid     Not measured (DASHSCOPE_API_KEY absent)");
                if (Arrays.asList(args).contains("--scaling")) scaling(data, null);
                if (Arrays.asList(args).contains("--online")) {
                    System.out.println("Online / end-to-end latency not measured (DASHSCOPE_API_KEY absent)");
                }
                return;
            }
            EmbeddingProvider remote = hasKey
                ? new OpenAiCompatibleEmbeddingProvider(baseUrl, key, model, dimensions)
                : texts -> { throw new IllegalStateException("Missing real cached embedding; set DASHSCOPE_API_KEY"); };
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
            if (Arrays.asList(args).contains("--online")) {
                if (hasKey) online(data, registry, bm25, cache, remote);
                else System.out.println("Online / end-to-end latency not measured (DASHSCOPE_API_KEY absent)");
            }
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

    private static void printLatency(String name, Latency latency) {
        System.out.printf(Locale.ROOT, "%-14s P50=%.3f ms  P95=%.3f ms%n",
            name, latency.p50(), latency.p95());
    }

    static double percentileMillis(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) throw new IllegalArgumentException("latency samples must not be empty");
        return sorted.get((int) Math.ceil(percentile * sorted.size()) - 1) / 1e6;
    }

    static Latency timeRoutes(ToolRouter router, List<Case> sample, int warmupCalls, int rounds) {
        if (sample.isEmpty() || warmupCalls < 0 || rounds <= 0) {
            throw new IllegalArgumentException("nonempty sample, nonnegative warmup and positive rounds required");
        }
        for (int i = 0; i < warmupCalls; i++) router.route(sample.get(i % sample.size()).query(), 5);
        List<Long> times = new ArrayList<>(sample.size() * rounds);
        for (int round = 0; round < rounds; round++) {
            for (Case item : sample) {
                long start = System.nanoTime();
                router.route(item.query(), 5);
                times.add(System.nanoTime() - start);
            }
        }
        Collections.sort(times);
        return new Latency(percentileMillis(times, 0.50), percentileMillis(times, 0.95));
    }

    private static List<Case> sample(List<Case> queries) {
        List<Case> shuffled = new ArrayList<>(queries);
        Collections.shuffle(shuffled, new java.util.Random(42));
        return List.copyOf(shuffled.subList(0, Math.min(50, shuffled.size())));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Only catalog batches use the cache; each query is sent to the remote provider. */
    static EmbeddingProvider onlineProvider(EmbeddingProvider catalogCache, EmbeddingProvider remote) {
        return new EmbeddingProvider() {
            @Override public List<float[]> embed(List<String> texts) { return catalogCache.embed(texts); }
            @Override public float[] embed(String text) { return remote.embed(text); }
        };
    }

    private static void online(Dataset data, InMemoryToolRegistry registry, BM25ToolRouter bm25,
                               EmbeddingProvider catalogCache, EmbeddingProvider remote) {
        List<Case> queries = sample(data.queries());
        VectorToolRouter vector = new VectorToolRouter(registry, onlineProvider(catalogCache, remote));
        // Build the tool index before timing. This call also warms one remote request.
        vector.route(queries.get(0).query(), 5);
        HybridToolRouter hybrid = new HybridToolRouter(bm25, vector);
        System.out.printf("Online / end-to-end routing latency: %d seeded queries, 3 warmup calls per strategy%n",
            queries.size());
        System.out.println("Includes one uncached query embedding API call per route; excludes tool-index construction");
        printLatency("Embedding E2E", timeRoutes(vector, queries, 3, 1));
        printLatency("Hybrid E2E", timeRoutes(hybrid, queries, 3, 1));
    }

    private static void scaling(Dataset data, EmbeddingProvider provider) {
        List<Case> queries = sample(data.queries());
        System.out.printf("Scaling: %d seeded queries, 10 warmup rounds and 20 measured rounds per size/strategy%n",
            queries.size());
        System.out.println("Retrieval-only latency; prebuilt indices and hot query vectors");
        if (provider != null) provider.embed(queries.stream().map(Case::query).toList());
        for (int size : new int[]{100, 500, 1000}) {
            InMemoryToolRegistry registry = new InMemoryToolRegistry();
            for (int i = 0; i < size; i++) {
                ToolDefinition source = data.tools().get(i % data.tools().size());
                registry.register(new ToolDefinition(source.name() + "_" + i,
                    source.description(), source.tags(), null));
            }
            try (BM25ToolRouter bm25 = new BM25ToolRouter(registry)) {
                bm25.route(queries.get(0).query(), 5);
                VectorToolRouter vector = provider == null ? null : new VectorToolRouter(registry, provider);
                if (vector != null) vector.route(queries.get(0).query(), 5);
                System.out.printf("%d tools%n", size);
                printLatency("BM25", timeRoutes(bm25, queries, 10 * queries.size(), 20));
                if (vector != null) {
                    printLatency("Vector", timeRoutes(vector, queries, 10 * queries.size(), 20));
                    printLatency("Hybrid", timeRoutes(new HybridToolRouter(bm25, vector),
                        queries, 10 * queries.size(), 20));
                }
            }
        }
    }
}
