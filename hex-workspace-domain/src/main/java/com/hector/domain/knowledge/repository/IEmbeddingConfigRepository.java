package com.hector.domain.knowledge.repository;

import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;

import java.util.Optional;

/** 不可变 Embedding 向量空间配置快照仓储。 */
public interface IEmbeddingConfigRepository {

    /**
     * 按配置摘要保存不可变快照；相同摘要必须复用既有记录。
     *
     * @param config 本次构建采用的 Embedding 配置
     * @return 已持久化的配置快照，包含数据库主键
     */
    EmbeddingConfigEntity saveIfAbsent(EmbeddingConfigEntity config);

    /**
     * 按主键查询历史配置快照。
     *
     * @param id 配置主键
     * @return 配置存在时返回对应快照
     */
    Optional<EmbeddingConfigEntity> findById(Long id);

    /**
     * 按内容摘要查询配置快照，用于幂等复用。
     *
     * @param configHash 配置内容摘要
     * @return 配置存在时返回对应快照
     */
    Optional<EmbeddingConfigEntity> findByConfigHash(String configHash);
}
