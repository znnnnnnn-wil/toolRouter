package io.github.toolrouter.model;

import java.util.List;

/**
 * 候选工具及相对分差提示；该提示未经置信度校准。
 */
public record RouteResponse(List<RouteResult> candidates, double scoreGapHint, boolean fallbackRecommended) {
    public RouteResponse {
        candidates = List.copyOf(candidates);
    }
}
