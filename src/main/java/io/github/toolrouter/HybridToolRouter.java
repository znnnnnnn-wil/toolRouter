package io.github.toolrouter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reciprocal Rank Fusion of lexical and vector candidate rankings. */
public final class HybridToolRouter implements ToolRouter {
    private record Acc(ToolDefinition tool, double score) {}
    private final ToolRouter lexical;
    private final ToolRouter semantic;
    private final int rrfK;
    private final int poolSize;

    public HybridToolRouter(ToolRouter lexical, ToolRouter semantic) { this(lexical, semantic, 60, 50); }

    public HybridToolRouter(ToolRouter lexical, ToolRouter semantic, int rrfK, int poolSize) {
        if (rrfK <= 0 || poolSize <= 0) throw new IllegalArgumentException("RRF k and poolSize must be positive");
        this.lexical = lexical;
        this.semantic = semantic;
        this.rrfK = rrfK;
        this.poolSize = poolSize;
    }

    @Override public List<RouteResult> route(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        int depth = Math.max(topK, poolSize);
        Map<String, Acc> scores = new HashMap<>();
        add(scores, lexical.route(query, depth));
        add(scores, semantic.route(query, depth));
        List<Acc> sorted = new ArrayList<>(scores.values());
        sorted.sort(Comparator.comparingDouble(Acc::score).reversed().thenComparing(a -> a.tool().name()));
        List<RouteResult> results = new ArrayList<>();
        for (Acc acc : sorted) {
            if (results.size() == topK) break;
            results.add(new RouteResult(acc.tool(), acc.score(), results.size() + 1));
        }
        return List.copyOf(results);
    }

    private void add(Map<String, Acc> scores, List<RouteResult> ranking) {
        for (RouteResult result : ranking) {
            String name = result.tool().name();
            Acc before = scores.get(name);
            scores.put(name, new Acc(result.tool(), (before == null ? 0 : before.score()) + 1.0 / (rrfK + result.rank())));
        }
    }
}
