package com.hector.domain.knowledge.repository;

import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;

import java.util.Optional;

/** 当前部署唯一生效的 Embedding 向量空间权威指针。 */
public interface IServingVectorSpaceRepository {

    /**
     * 查询当前权威配置快照。
     *
     * @return 未初始化时为空；发布流程必须拒绝未初始化状态
     */
    Optional<EmbeddingConfigEntity> findServingConfig();

    /**
     * 仅在首次部署初始化时写入权威配置。
     * 不覆盖既有值，避免不同实例的应用配置静默切换 Serving Vector Space。
     *
     * @param embeddingConfigId 不可变 Embedding 配置快照主键
     * @return 是否完成首次初始化
     */
    boolean initializeIfAbsent(Long embeddingConfigId);
}
