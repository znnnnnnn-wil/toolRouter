package io.github.toolrouter.routing;

import io.github.toolrouter.model.ToolDefinition;
import io.github.toolrouter.model.RouteResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用倒数排名融合（RRF）合并关键词检索和向量检索的候选排名。
 */
public final class HybridToolRouter implements ToolRouter {
    private record Acc(ToolDefinition tool, double score) {
    }

    private final ToolRouter lexical;
    private final ToolRouter semantic;
    private final int rrfK;
    private final int poolSize;

    public HybridToolRouter(ToolRouter lexical, ToolRouter semantic) {
        this(lexical, semantic, 60, 50);
    }

    public HybridToolRouter(ToolRouter lexical, ToolRouter semantic, int rrfK, int poolSize) {
        if (rrfK <= 0 || poolSize <= 0) {
            throw new IllegalArgumentException("RRF k and poolSize must be positive");
        }
        this.lexical = lexical;
        this.semantic = semantic;
        this.rrfK = rrfK;
        this.poolSize = poolSize;
    }

    @Override
    public List<RouteResult> route(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        // 先扩大两路候选池，再融合截断，避免过早丢弃可互补的候选。
        int depth = Math.max(topK, poolSize);
        Map<String, Acc> scores = new HashMap<>();
        add(scores, lexical.route(query, depth));
        add(scores, semantic.route(query, depth));
        List<Acc> sorted = new ArrayList<>(scores.values());
        sorted.sort(Comparator.comparingDouble(Acc::score)
            .reversed()
            .thenComparing(a -> a.tool().name()));
        List<RouteResult> results = new ArrayList<>();
        for (Acc acc : sorted) {
            if (results.size() == topK) {
                break;
            }
            results.add(new RouteResult(acc.tool(), acc.score(), results.size() + 1));
        }
        return List.copyOf(results);
    }

    private void add(Map<String, Acc> scores, List<RouteResult> ranking) {
        for (RouteResult result : ranking) {
            String name = result.tool().name();
            Acc before = scores.get(name);
            // 按工具名称合并候选，累加 1 / (k + 排名)，避免直接比较两路原始分数。
            scores.put(name, new Acc(result.tool(),
                (before == null ? 0 : before.score()) + 1.0 / (rrfK + result.rank())));
        }
    }
}
