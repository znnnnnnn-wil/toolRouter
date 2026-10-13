package io.github.toolrouter.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * 可调用工具的不可变描述；输入结构在写入和读取时均做深拷贝。
 */
public record ToolDefinition(String name, String description, List<String> tags, JsonNode inputSchema) {
    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        name = name.trim();
        description = description == null ? "" : description;
        tags = List.copyOf(Objects.requireNonNullElse(tags, List.of()));
        inputSchema = inputSchema == null ? null : inputSchema.deepCopy();
    }

    /**
     * 返回输入结构的副本，避免调用方修改工具内部状态。
     */
    @Override
    public JsonNode inputSchema() {
        return inputSchema == null ? null : inputSchema.deepCopy();
    }
}
