package com.hector.domain.knowledge.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;

import java.time.LocalDateTime;
import java.util.Optional;

/** 每次知识索引构建尝试的 Generation 仓储。 */
public interface IKnowledgeIndexGenerationRepository {

    /**
     * 保存一次独立的索引构建尝试。
     *
     * @param generation 本次构建的 Generation 元数据
     * @return 回填主键后的 Generation
     */
    KnowledgeIndexGenerationEntity save(KnowledgeIndexGenerationEntity generation);

    /**
     * 按 Generation 标识查询构建状态与产物元数据。
     *
     * @param generationId Generation 唯一标识
     * @return Generation 存在时返回其元数据
     */
    Optional<KnowledgeIndexGenerationEntity> findByGenerationId(String generationId);

    /**
     * 仅当当前 Worker 仍持有有效 Job 租约且 Generation 为 BUILDING 时附加 Chunk Artifact。
     */
    boolean attachChunkArtifactIfOwned(String generationId, String workerToken, String artifactKey,
                                       String artifactChecksum, int chunkCount, LocalDateTime now);

    /**
     * 仅当前有效 Worker 可将完整 Generation 标记为 READY 并绑定 Embedding 配置快照。
     */
    boolean markReadyIfOwned(String generationId, String workerToken, Long embeddingConfigId, LocalDateTime now);

    /**
     * 将当前 Worker 尚未发布的 BUILDING Generation 标记为失败。
     */
    boolean markFailedIfOwned(String generationId, String workerToken);
}
