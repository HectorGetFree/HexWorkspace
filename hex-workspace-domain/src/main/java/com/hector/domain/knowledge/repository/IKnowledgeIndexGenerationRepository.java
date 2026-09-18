package com.hector.domain.knowledge.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;

/** 每次知识索引构建尝试的 Generation 仓储。 */
public interface IKnowledgeIndexGenerationRepository {

    KnowledgeIndexGenerationEntity save(KnowledgeIndexGenerationEntity generation);

    boolean attachChunkArtifactIfOwned(String generationId, String workerToken, String artifactKey,
                                       String artifactChecksum, int chunkCount);
}
