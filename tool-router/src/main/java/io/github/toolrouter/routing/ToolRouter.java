package io.github.toolrouter.routing;

import io.github.toolrouter.model.RouteResult;
import io.github.toolrouter.model.RouteResponse;

import java.util.List;

/**
 * 检索候选工具，最终选择由调用方决定。
 */
public interface ToolRouter {
    /**
     * 返回最多 topK 个候选，按相关性排序，排名从 1 开始。
     */
    List<RouteResult> route(String query, int topK);

    /**
     * 至少检索两个候选以计算分差；候选不足或分差过小时建议调用方采用回退策略。
     */
    default RouteResponse routeWithHint(String query, int topK) {
        if (topK <= 0) {
            return new RouteResponse(List.of(), 0, true);
        }
        List<RouteResult> ranked = route(query, Math.max(topK, 2));
        List<RouteResult> candidates = ranked.size() > topK
            ? ranked.subList(0, topK) : ranked;
        if (ranked.size() < 2) {
            return new RouteResponse(candidates, 0, true);
        }
        // 不同策略的分数尺度不同，分差仅在同一路由器内部比较，不能视为校准后的置信度。
        double first = ranked.get(0).score();
        double second = ranked.get(1).score();
        double gap = first <= 0 ? 0 : Math.max(0, (first - second) / first);
        return new RouteResponse(candidates, gap, first <= 0 || gap < 0.1);
    }
}
