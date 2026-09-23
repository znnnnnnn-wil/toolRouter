package io.github.toolrouter;

import java.util.List;

/** Produces equal-dimension vectors for a batch of texts. */
public interface EmbeddingProvider {
    List<float[]> embed(List<String> texts);
    default float[] embed(String text) { return embed(List.of(text)).get(0); }
}
