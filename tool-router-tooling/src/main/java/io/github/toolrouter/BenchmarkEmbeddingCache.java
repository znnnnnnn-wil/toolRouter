package io.github.toolrouter;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent benchmark cache for model outputs. Cache keys include endpoint,
 * model, dimensions and exact text; credentials never enter keys or files.
 */
final class BenchmarkEmbeddingCache implements EmbeddingProvider {
    private final EmbeddingProvider delegate;
    private final Path directory;
    private final String namespace;
    private final int dimensions;
    private final Map<String, float[]> memory = new HashMap<>();

    BenchmarkEmbeddingCache(EmbeddingProvider delegate, Path directory, String namespace, int dimensions) {
        this.delegate = delegate;
        this.directory = directory;
        this.namespace = namespace;
        this.dimensions = dimensions;
    }

    @Override public synchronized List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        Map<String, Path> missing = new LinkedHashMap<>();
        for (String text : texts) {
            if (memory.containsKey(text)) continue;
            Path path = directory.resolve(hash(namespace + "\n" + text) + ".bin");
            float[] cached = read(path);
            if (cached != null) memory.put(text, cached);
            else missing.putIfAbsent(text, path);
        }
        if (!missing.isEmpty()) {
            List<String> inputs = List.copyOf(missing.keySet());
            List<float[]> vectors = delegate.embed(inputs);
            if (vectors.size() != inputs.size()) throw new IllegalStateException("Embedding count mismatch");
            for (int i = 0; i < inputs.size(); i++) {
                float[] vector = vectors.get(i).clone();
                if (vector.length != dimensions) throw new IllegalStateException("Embedding dimension mismatch");
                memory.put(inputs.get(i), vector);
                write(missing.get(inputs.get(i)), vector);
            }
        }
        List<float[]> results = new ArrayList<>(texts.size());
        for (String text : texts) results.add(memory.get(text).clone());
        return List.copyOf(results);
    }

    private float[] read(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            int size = in.readInt();
            if (size != dimensions) return null;
            float[] vector = new float[size];
            for (int i = 0; i < size; i++) vector[i] = in.readFloat();
            if (in.read() != -1) return null;
            return vector;
        } catch (IOException e) {
            return null;
        }
    }

    private void write(Path path, float[] vector) {
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "embedding-", ".tmp");
            try {
                try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temporary))) {
                    out.writeInt(vector.length);
                    for (float value : vector) out.writeFloat(value);
                }
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write benchmark embedding cache", e);
        }
    }

    private static String hash(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
