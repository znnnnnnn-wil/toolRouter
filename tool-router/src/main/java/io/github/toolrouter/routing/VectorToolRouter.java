package io.github.toolrouter.routing;

import io.github.toolrouter.model.ToolDefinition;
import io.github.toolrouter.model.ToolTextBuilder;
import io.github.toolrouter.model.RouteResult;
import io.github.toolrouter.registry.InMemoryToolRegistry;
import io.github.toolrouter.embedding.EmbeddingProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 基于余弦相似度的内存向量检索；单次查询复杂度为 O(ND + N log K)，存储复杂度为 O(ND)。
 */
public final class VectorToolRouter implements ToolRouter {
    private record Entry(ToolDefinition tool, String text, float[] vector) {
    }

    private record Scored(ToolDefinition tool, double score) {
    }

    private record Index(long version, List<Entry> entries) {
    }

    private final InMemoryToolRegistry registry;
    private final EmbeddingProvider provider;
    private final ToolTextBuilder textBuilder;
    private volatile Index index = new Index(-1, List.of());

    public VectorToolRouter(InMemoryToolRegistry registry, EmbeddingProvider provider) {
        this(registry, provider, new ToolTextBuilder());
    }

    public VectorToolRouter(InMemoryToolRegistry registry, EmbeddingProvider provider, ToolTextBuilder textBuilder) {
        this.registry = registry;
        this.provider = provider;
        this.textBuilder = textBuilder;
    }

    private Index current(InMemoryToolRegistry.Snapshot snapshot) {
        Index observed = index;
        if (observed.version() == snapshot.version()) {
            return observed;
        }
        return rebuild(snapshot);
    }

    private synchronized Index rebuild(InMemoryToolRegistry.Snapshot snapshot) {
        Index previous = index;
        // 并发请求可能携带旧快照，禁止用旧版本覆盖已发布的新索引。
        if (previous.version() >= snapshot.version()) {
            return previous;
        }
        Map<String, Entry> existing = new HashMap<>();
        for (Entry entry : previous.entries()) {
            existing.put(entry.tool().name(), entry);
        }
        List<ToolDefinition> tools = List.copyOf(snapshot.tools().values());
        List<String> texts = tools.stream().map(textBuilder::build).toList();
        // 仅为新增或检索文本发生变化的工具生成向量，元数据更新复用原向量。
        List<String> changedTexts = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            Entry old = existing.get(tools.get(i).name());
            if (old == null || !old.text().equals(texts.get(i))) {
                changedTexts.add(texts.get(i));
            }
        }
        List<float[]> changedVectors = changedTexts.isEmpty() ? List.of() : provider.embed(changedTexts);
        if (changedVectors.size() != changedTexts.size()) {
            throw new IllegalStateException("Embedding count mismatch");
        }
        List<Entry> entries = new ArrayList<>(tools.size());
        int changedIndex = 0;
        for (int i = 0; i < tools.size(); i++) {
            ToolDefinition tool = tools.get(i);
            String text = texts.get(i);
            Entry old = existing.get(tool.name());
            float[] vector = old != null && old.text().equals(text)
                ? old.vector() : changedVectors.get(changedIndex++).clone();
            entries.add(new Entry(tool, text, vector));
        }
        Index built = new Index(snapshot.version(), List.copyOf(entries));
        // 完整构建后一次性发布不可变索引，查询不会读到中间状态。
        index = built;
        return built;
    }

    /**
     * 计算余弦相似度，并校验向量维度及数值是否有限；零向量的相似度为 0。
     */
    public static double cosine(float[] left, float[] right) {
        if (left.length == 0 || left.length != right.length) {
            throw new IllegalArgumentException("embedding dimensions differ");
        }
        double dot = 0;
        double a = 0;
        double b = 0;
        for (int i = 0; i < left.length; i++) {
            if (!Float.isFinite(left[i]) || !Float.isFinite(right[i])) {
                throw new IllegalArgumentException("non-finite embedding");
            }
            dot += (double) left[i] * right[i];
            a += (double) left[i] * left[i];
            b += (double) right[i] * right[i];
        }
        return a == 0 || b == 0 ? 0 : dot / Math.sqrt(a * b);
    }

    @Override
    public List<RouteResult> route(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        Index snapshot = current(registry.snapshot());
        if (snapshot.entries().isEmpty()) {
            return List.of();
        }
        float[] queryVector = provider.embed(query);
        // 堆顶保留当前最差候选，最多存储 topK 个；同分时按工具名称稳定排序。
        Comparator<Scored> worstFirst = Comparator.comparingDouble(Scored::score)
            .thenComparing(s -> s.tool().name(), Comparator.reverseOrder());
        PriorityQueue<Scored> heap = new PriorityQueue<>(worstFirst);
        for (Entry entry : snapshot.entries()) {
            Scored scored = new Scored(entry.tool(), cosine(queryVector, entry.vector()));
            if (heap.size() < topK) {
                heap.add(scored);
            } else if (worstFirst.compare(scored, heap.peek()) > 0) {
                heap.poll();
                heap.add(scored);
            }
        }
        List<Scored> sorted = new ArrayList<>(heap);
        // 堆只保证堆顶顺序，返回前需将全部候选按分数降序排列。
        sorted.sort(worstFirst.reversed());
        List<RouteResult> results = new ArrayList<>();
        for (Scored scored : sorted) {
            results.add(new RouteResult(scored.tool(), scored.score(), results.size() + 1));
        }
        return List.copyOf(results);
    }
}
