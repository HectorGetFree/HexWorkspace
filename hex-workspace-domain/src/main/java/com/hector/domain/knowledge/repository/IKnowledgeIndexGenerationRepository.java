package com.hector.domain.knowledge.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;

import java.time.LocalDateTime;
import java.util.List;
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

    /** 在发布事务中锁定 Generation，避免与延迟清理领取发生竞争。 */
    Optional<KnowledgeIndexGenerationEntity> lockByGenerationId(String generationId);

    /** 批量读取候选 Generation，供检索链路验证其不可变身份。 */
    List<KnowledgeIndexGenerationEntity> listByGenerationIds(List<String> generationIds);

    /** 查询已过期且未被当前 Active Generation 引用的清理候选。 */
    List<KnowledgeIndexGenerationEntity> listCleanupCandidates(
            LocalDateTime expiredBefore, LocalDateTime now, int limit);

    /** 以条件更新领取清理租约，禁止领取当前 Active Generation。 */
    boolean claimForCleanup(String generationId, String cleanupToken,
                            LocalDateTime expiredBefore, LocalDateTime leaseUntil, LocalDateTime now);

    /** 仅当前清理 Worker 可将 Generation 标记为 CLEANED。 */
    boolean markCleanedIfOwned(String generationId, String cleanupToken, LocalDateTime now);

    /** 清理失败时释放当前 Worker 持有的清理租约。 */
    boolean releaseCleanupClaim(String generationId, String cleanupToken, LocalDateTime now);

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
    boolean markFailedIfOwned(String generationId, String workerToken, LocalDateTime now);
}
