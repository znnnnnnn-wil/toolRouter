package io.github.toolrouter.registry;

import io.github.toolrouter.model.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 写操作加锁并发布带版本号的不可变快照，读操作无需加锁。
 */
public final class InMemoryToolRegistry {
    /**
     * 同一版本的工具集合；注册表发布的集合保持注册顺序且不可修改。
     */
    public record Snapshot(long version, Map<String, ToolDefinition> tools) {
    }

    private volatile Snapshot current = new Snapshot(0, Map.of());

    public synchronized void register(ToolDefinition tool) {
        if (current.tools().containsKey(tool.name())) {
            throw new IllegalArgumentException("duplicate tool: " + tool.name());
        }
        put(tool);
    }

    public synchronized void update(ToolDefinition tool) {
        if (!current.tools().containsKey(tool.name())) {
            throw new IllegalArgumentException("unknown tool: " + tool.name());
        }
        put(tool);
    }

    // 调用方持有写锁，先复制集合再递增版本并发布，已有快照保持不变。
    private void put(ToolDefinition tool) {
        Map<String, ToolDefinition> next = new LinkedHashMap<>(current.tools());
        next.put(tool.name(), tool);
        current = new Snapshot(current.version() + 1, Collections.unmodifiableMap(next));
    }

    public synchronized boolean remove(String name) {
        if (!current.tools().containsKey(name)) {
            return false;
        }
        Map<String, ToolDefinition> next = new LinkedHashMap<>(current.tools());
        next.remove(name);
        current = new Snapshot(current.version() + 1, Collections.unmodifiableMap(next));
        return true;
    }

    public Optional<ToolDefinition> get(String name) {
        return Optional.ofNullable(current.tools().get(name));
    }

    public List<ToolDefinition> list() {
        return List.copyOf(current.tools().values());
    }

    public int size() {
        return current.tools().size();
    }

    public Snapshot snapshot() {
        return current;
    }
}
