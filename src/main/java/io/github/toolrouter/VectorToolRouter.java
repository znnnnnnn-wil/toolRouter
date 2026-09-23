package io.github.toolrouter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** In-memory cosine retriever; O(ND + N log K) per query, O(ND) storage. */
public final class VectorToolRouter implements ToolRouter {
    private record Entry(ToolDefinition tool, float[] vector) {}
    private record Scored(ToolDefinition tool, double score) {}
    private record Index(long version, List<Entry> entries) {}
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
        if (observed.version() == snapshot.version()) return observed;
        return rebuild(snapshot);
    }

    private synchronized Index rebuild(InMemoryToolRegistry.Snapshot snapshot) {
        if (index.version() == snapshot.version()) return index;
        List<ToolDefinition> tools = List.copyOf(snapshot.tools().values());
        List<String> texts = tools.stream().map(textBuilder::build).toList();
        List<float[]> vectors = provider.embed(texts);
        if (vectors.size() != tools.size()) throw new IllegalStateException("Embedding count mismatch");
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) entries.add(new Entry(tools.get(i), vectors.get(i).clone()));
        Index built = new Index(snapshot.version(), List.copyOf(entries));
        if (snapshot.version() > index.version()) index = built;
        return built;
    }

    /** Cosine similarity with dimension and finite-value validation. */
    public static double cosine(float[] left, float[] right) {
        if (left.length == 0 || left.length != right.length) throw new IllegalArgumentException("embedding dimensions differ");
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < left.length; i++) {
            if (!Float.isFinite(left[i]) || !Float.isFinite(right[i])) throw new IllegalArgumentException("non-finite embedding");
            dot += (double) left[i] * right[i];
            a += (double) left[i] * left[i];
            b += (double) right[i] * right[i];
        }
        return a == 0 || b == 0 ? 0 : dot / Math.sqrt(a * b);
    }

    @Override public List<RouteResult> route(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        Index snapshot = current(registry.snapshot());
        if (snapshot.entries().isEmpty()) return List.of();
        float[] queryVector = provider.embed(query);
        Comparator<Scored> worstFirst = Comparator.comparingDouble(Scored::score).thenComparing(s -> s.tool().name(), Comparator.reverseOrder());
        PriorityQueue<Scored> heap = new PriorityQueue<>(worstFirst);
        for (Entry entry : snapshot.entries()) {
            Scored scored = new Scored(entry.tool(), cosine(queryVector, entry.vector()));
            if (heap.size() < topK) heap.add(scored);
            else if (worstFirst.compare(scored, heap.peek()) > 0) { heap.poll(); heap.add(scored); }
        }
        List<Scored> sorted = new ArrayList<>(heap);
        sorted.sort(worstFirst.reversed());
        List<RouteResult> results = new ArrayList<>();
        for (Scored scored : sorted) results.add(new RouteResult(scored.tool(), scored.score(), results.size() + 1));
        return List.copyOf(results);
    }
}
