package io.github.toolrouter;

/** Ranked tool candidate. Rank starts at one. */
public record RouteResult(ToolDefinition tool, double score, int rank) {}
