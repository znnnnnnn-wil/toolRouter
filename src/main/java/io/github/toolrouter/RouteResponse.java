package io.github.toolrouter;

import java.util.List;

/** Candidates and a conservative uncertainty signal. */
public record RouteResponse(List<RouteResult> candidates, double confidence, boolean fallbackRecommended) {
    public RouteResponse { candidates = List.copyOf(candidates); }
}
