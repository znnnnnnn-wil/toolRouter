package io.github.toolrouter;

import java.util.ArrayList;
import java.util.List;

/** Minimal command-line example with selectable retrieval strategy. */
public final class Example {
    public static void main(String[] args) {
        String strategy = "bm25";
        List<String> words = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--router")) {
                if (++i == args.length) throw new IllegalArgumentException("--router requires bm25, vector or hybrid");
                strategy = args[i];
            } else {
                words.add(args[i]);
            }
        }
        String query = words.isEmpty() ? "I want my money back for yesterday's purchase" : String.join(" ", words);
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new ToolDefinition("refund_payment", "Return money from a captured payment",
            List.of("refund", "money", "payment"), null));
        registry.register(new ToolDefinition("cancel_order", "Stop an order before it ships",
            List.of("order", "cancel"), null));
        registry.register(new ToolDefinition("get_order", "Read order status and details",
            List.of("order", "status"), null));
        try (BM25ToolRouter bm25 = new BM25ToolRouter(registry)) {
            ToolRouter router;
            if (strategy.equals("bm25")) {
                router = bm25;
            } else if (strategy.equals("vector") || strategy.equals("hybrid")) {
                String key = System.getenv("DASHSCOPE_API_KEY");
                if (key == null || key.isBlank()) throw new IllegalStateException("Set DASHSCOPE_API_KEY");
                String url = env("EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1");
                String model = env("EMBEDDING_MODEL", "text-embedding-v4");
                int dimensions = Integer.parseInt(env("EMBEDDING_DIMENSIONS", "1024"));
                ToolRouter vector = new VectorToolRouter(registry,
                    new OpenAiCompatibleEmbeddingProvider(url, key, model, dimensions));
                router = strategy.equals("vector") ? vector : new HybridToolRouter(bm25, vector);
            } else {
                throw new IllegalArgumentException("Unknown router: " + strategy);
            }
            System.out.println("Query: " + query + "\nTop tools:");
            router.route(query, 5).forEach(result ->
                System.out.printf("%d. %s (%.3f)%n", result.rank(), result.tool().name(), result.score()));
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
