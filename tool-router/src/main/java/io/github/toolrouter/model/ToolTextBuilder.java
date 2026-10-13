package io.github.toolrouter.model;

/**
 * 生成稳定的工具检索文本，供向量生成和基准测试共用。
 */
public final class ToolTextBuilder {
    public String build(ToolDefinition tool) {
        return "Name: " + tool.name().replace('_', ' ') + "\nDescription: " + tool.description()
            + "\nTags: " + String.join(", ", tool.tags());
    }
}
