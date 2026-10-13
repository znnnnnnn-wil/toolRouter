package io.github.toolrouter.embedding;

import java.util.List;

/**
 * 将一批文本转换为相同维度的向量，返回顺序与输入顺序一致。
 */
public interface EmbeddingProvider {
    /**
     * 批量生成向量，实现应保证返回数量与输入数量一致。
     */
    List<float[]> embed(List<String> texts);
    /**
     * 单条文本复用批量接口。
     */
    default float[] embed(String text) {
        return embed(List.of(text)).get(0);
    }
}
