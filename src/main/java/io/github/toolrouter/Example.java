package io.github.toolrouter;

import java.util.List;

/** Minimal command-line example. */
public final class Example {
    public static void main(String[] args) {
        String query = args.length == 0 ? "I want my money back for yesterday's purchase" : String.join(" ", args);
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new ToolDefinition("refund_payment", "Return money from a captured payment", List.of("refund", "money", "payment"), null));
        registry.register(new ToolDefinition("cancel_order", "Stop an order before it ships", List.of("order", "cancel"), null));
        registry.register(new ToolDefinition("get_order", "Read order status and details", List.of("order", "status"), null));
        try (BM25ToolRouter router = new BM25ToolRouter(registry)) {
            System.out.println("Query: " + query + "\nTop tools:");
            router.route(query, 5).forEach(result -> System.out.printf("%d. %s (%.3f)%n", result.rank(), result.tool().name(), result.score()));
        }
    }
}
