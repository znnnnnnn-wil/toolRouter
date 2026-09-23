package io.github.toolrouter;

import java.util.List;

/** Retrieves candidate tools; final tool selection belongs to the caller. */
public interface ToolRouter {
    List<RouteResult> route(String query, int topK);

    default RouteResponse routeWithConfidence(String query, int topK) {
        List<RouteResult> results = route(query, topK);
        if (results.isEmpty()) return new RouteResponse(results, 0, true);
        if (results.size() == 1) return new RouteResponse(results, 0, true);
        // Scores differ between strategies. The rank gap is meaningful only when
        // both candidates come from the same router, and is advisory rather than calibrated.
        double first = results.get(0).score();
        double second = results.size() > 1 ? results.get(1).score() : 0;
        double gap = first <= 0 ? 0 : Math.max(0, (first - second) / first);
        return new RouteResponse(results, gap, first <= 0 || gap < 0.1);
    }
}
