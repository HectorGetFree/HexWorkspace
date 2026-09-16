package com.hector.domain.knowledge.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** 单次索引构建尝试的不可变身份记录；Phase 1 仅建模，不执行构建。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeIndexGenerationEntity {
    /** 主键。 */
    private Long id;

    /** 每次执行尝试独立生成的唯一标识。 */
    private String generationId;

    /** 来源索引任务主键。 */
    private Long jobId;

    /** 所属资料库。 */
    private Long workspaceId;

    /** 所属文档。 */
    private Long documentId;

    /** 本次构建绑定的内容版本号。 */
    private Long versionNo;

    /** 本次构建绑定的索引输入修订号。 */
    private Long indexInputRevision;

    /** 使用的 Embedding 配置快照主键。 */
    private Long embeddingConfigId;

    /** Chunking 配置摘要。 */
    private String chunkingConfigHash;

    /** Chunk 产物对象存储路径。 */
    private String chunkArtifactKey;

    /** Chunk 产物内容摘要。 */
    private String chunkArtifactChecksum;

    /** 产出的 Chunk 数量。 */
    private Integer chunkCount;

    /** 构建状态，0=BUILDING、1=READY、2=FAILED。 */
    private Integer status;

    /** 执行本次构建的 Worker 令牌。 */
    private String workerToken;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最后更新时间。 */
    private LocalDateTime updatedAt;
}
