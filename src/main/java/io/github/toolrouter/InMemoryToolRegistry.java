package io.github.toolrouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Synchronized writes publish immutable, versioned snapshots for lock-free reads. */
public final class InMemoryToolRegistry {
    public record Snapshot(long version, Map<String, ToolDefinition> tools) {}
    private volatile Snapshot current = new Snapshot(0, Map.of());

    public synchronized void register(ToolDefinition tool) {
        if (current.tools().containsKey(tool.name())) throw new IllegalArgumentException("duplicate tool: " + tool.name());
        put(tool);
    }

    public synchronized void update(ToolDefinition tool) {
        if (!current.tools().containsKey(tool.name())) throw new IllegalArgumentException("unknown tool: " + tool.name());
        put(tool);
    }

    private void put(ToolDefinition tool) {
        Map<String, ToolDefinition> next = new LinkedHashMap<>(current.tools());
        next.put(tool.name(), tool);
        current = new Snapshot(current.version() + 1, Collections.unmodifiableMap(next));
    }

    public synchronized boolean remove(String name) {
        if (!current.tools().containsKey(name)) return false;
        Map<String, ToolDefinition> next = new LinkedHashMap<>(current.tools());
        next.remove(name);
        current = new Snapshot(current.version() + 1, Collections.unmodifiableMap(next));
        return true;
    }

    public Optional<ToolDefinition> get(String name) { return Optional.ofNullable(current.tools().get(name)); }
    public List<ToolDefinition> list() { return List.copyOf(current.tools().values()); }
    public int size() { return current.tools().size(); }
    public Snapshot snapshot() { return current; }
}
