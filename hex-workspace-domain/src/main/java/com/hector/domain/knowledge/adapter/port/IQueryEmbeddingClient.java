package com.hector.domain.knowledge.adapter.port;

import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;

import java.util.List;

/** 以当前权威向量空间配置生成查询向量的外部能力端口。 */
public interface IQueryEmbeddingClient {

    /**
     * 使用指定不可变配置生成单条查询向量。
     * 实现必须拒绝与当前部署模型、维度或集合不一致的配置。
     */
    List<Float> embedQuery(EmbeddingConfigEntity servingConfig, String query);
}
