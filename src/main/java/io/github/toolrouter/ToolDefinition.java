package io.github.toolrouter;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/** Immutable description of a callable tool; schema is defensively copied. */
public record ToolDefinition(String name, String description, List<String> tags, JsonNode inputSchema) {
    public ToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        name = name.trim();
        description = description == null ? "" : description;
        tags = List.copyOf(Objects.requireNonNullElse(tags, List.of()));
        inputSchema = inputSchema == null ? null : inputSchema.deepCopy();
    }

    @Override public JsonNode inputSchema() { return inputSchema == null ? null : inputSchema.deepCopy(); }
}
