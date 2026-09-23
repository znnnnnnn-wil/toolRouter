package io.github.toolrouter;

import java.util.List;

/** Candidates and an uncalibrated relative score-gap hint. */
public record RouteResponse(List<RouteResult> candidates, double scoreGapHint, boolean fallbackRecommended) {
    public RouteResponse { candidates = List.copyOf(candidates); }
}
