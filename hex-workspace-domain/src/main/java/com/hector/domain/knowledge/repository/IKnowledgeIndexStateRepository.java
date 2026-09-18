package com.hector.domain.knowledge.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexStateEntity;

import java.util.Optional;

/** 已发布 Generation 权威指针仓储。 */
public interface IKnowledgeIndexStateRepository {

    /**
     * 查询文档当前已发布的权威 Generation。
     *
     * @param documentId 文档主键
     * @return 文档已经发布索引时返回其状态指针
     */
    Optional<KnowledgeIndexStateEntity> findByDocumentId(Long documentId);

    /**
     * 将文档的权威索引指针切换至指定 READY Generation。
     * 此方法必须由发布事务调用，不能独立作为可读索引切换入口。
     *
     * @param documentId 文档主键
     * @param generationId 已完整构建的 Generation 标识
     */
    void activate(Long documentId, String generationId);
}
