package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.KnowledgeIndexGeneration;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 单次索引构建尝试的 MyBatis DAO。 */
@Mapper
public interface IKnowledgeIndexGenerationDao {
    /** 保存新的 BUILDING Generation。 */
    int insert(KnowledgeIndexGeneration generation);

    /** 按业务编码查询 Generation。 */
    KnowledgeIndexGeneration queryByGenerationId(@Param("generationId") String generationId);

    /** 仅有效 Worker 可写入 Chunk Artifact 元数据。 */
    int attachChunkArtifactIfOwned(@Param("generationId") String generationId, @Param("workerToken") String workerToken,
                                   @Param("chunkArtifactKey") String chunkArtifactKey,
                                   @Param("chunkArtifactChecksum") String chunkArtifactChecksum,
                                   @Param("chunkCount") int chunkCount,
                                   @Param("now") LocalDateTime now);

    /** 仅有效 Worker 可将完整构建标记为 READY。 */
    int markReadyIfOwned(@Param("generationId") String generationId, @Param("workerToken") String workerToken,
                         @Param("embeddingConfigId") Long embeddingConfigId, @Param("now") LocalDateTime now);

    /** 仅持有未过期 Job 租约的 Worker 可标记尚未发布的构建失败。 */
    int markFailedIfOwned(@Param("generationId") String generationId, @Param("workerToken") String workerToken,
                          @Param("now") LocalDateTime now);
}
