package io.github.toolrouter;

import io.github.toolrouter.embedding.EmbeddingProvider;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkEmbeddingCacheTest {
    @TempDir Path cacheDirectory;

    @Test void benchmarkCachePersistsOnlyVectors() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingProvider delegate = texts -> {
            calls.incrementAndGet();
            return texts.stream().map(s -> new float[]{s.length(), 1}).toList();
        };
        BenchmarkEmbeddingCache first = new BenchmarkEmbeddingCache(delegate, cacheDirectory, "model-v1", 2);
        assertEquals(3, first.embed(List.of("a", "bb", "a")).size());
        assertEquals(1, calls.get());
        BenchmarkEmbeddingCache second = new BenchmarkEmbeddingCache(delegate, cacheDirectory, "model-v1", 2);
        assertArrayEquals(new float[]{1, 1}, second.embed("a"));
        assertEquals(1, calls.get());
        BenchmarkEmbeddingCache newModel = new BenchmarkEmbeddingCache(delegate, cacheDirectory, "model-v2", 2);
        newModel.embed("a");
        assertEquals(2, calls.get());
    }
}
