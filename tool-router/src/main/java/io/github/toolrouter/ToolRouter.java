package io.github.toolrouter;

import java.util.List;

/** Retrieves candidate tools; final tool selection belongs to the caller. */
public interface ToolRouter {
    List<RouteResult> route(String query, int topK);

    default RouteResponse routeWithHint(String query, int topK) {
        if (topK <= 0) return new RouteResponse(List.of(), 0, true);
        List<RouteResult> ranked = route(query, Math.max(topK, 2));
        List<RouteResult> candidates = ranked.size() > topK
            ? ranked.subList(0, topK) : ranked;
        if (ranked.size() < 2) return new RouteResponse(candidates, 0, true);
        // Scores differ between strategies. The rank gap is meaningful only when
        // both candidates come from the same router, and is advisory rather than calibrated.
        double first = ranked.get(0).score();
        double second = ranked.get(1).score();
        double gap = first <= 0 ? 0 : Math.max(0, (first - second) / first);
        return new RouteResponse(candidates, gap, first <= 0 || gap < 0.1);
    }
}
