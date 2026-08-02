package com.hector.domain.knowledge.adapter.port;

import java.util.List;

/**
 * Embedding 模型端口。替换模型时不影响知识领域的分段与检索编排。
 */
public interface IEmbeddingModel {

    List<List<Float>> embed(List<String> texts);

    String modelName();
}
