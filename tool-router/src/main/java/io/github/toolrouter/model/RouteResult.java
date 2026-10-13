package io.github.toolrouter.model;

/**
 * 带分数和排名的候选工具，排名从 1 开始。
 */
public record RouteResult(ToolDefinition tool, double score, int rank) {
}
