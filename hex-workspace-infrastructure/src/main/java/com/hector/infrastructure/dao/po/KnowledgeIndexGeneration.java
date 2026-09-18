package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** knowledge_index_generation 表映射对象。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeIndexGeneration {
    private Long id;
    private String generationId;
    private Long jobId;
    private Long workspaceId;
    private Long documentId;
    private Long versionNo;
    private Long indexInputRevision;
    private Long embeddingConfigId;
    private String chunkingConfigHash;
    private String chunkArtifactKey;
    private String chunkArtifactChecksum;
    private Integer chunkCount;
    private Integer status;
    private String workerToken;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
