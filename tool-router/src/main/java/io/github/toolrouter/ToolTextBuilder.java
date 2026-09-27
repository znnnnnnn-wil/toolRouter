package io.github.toolrouter;

/** Stable retrieval text shared by embedding providers and benchmarks. */
public final class ToolTextBuilder {
    public String build(ToolDefinition tool) {
        return "Name: " + tool.name().replace('_', ' ') + "\nDescription: " + tool.description()
            + "\nTags: " + String.join(", ", tool.tags());
    }
}
