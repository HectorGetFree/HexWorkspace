package com.hector.infrastructure.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.repository.IKnowledgeIndexGenerationRepository;
import com.hector.infrastructure.dao.IKnowledgeIndexGenerationDao;
import com.hector.infrastructure.dao.po.KnowledgeIndexGeneration;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/** Generation 元数据仓储实现。 */
@Repository
public class KnowledgeIndexGenerationRepository implements IKnowledgeIndexGenerationRepository {

    @Resource
    private IKnowledgeIndexGenerationDao knowledgeIndexGenerationDao;

    @Override
    public KnowledgeIndexGenerationEntity save(KnowledgeIndexGenerationEntity generation) {
        KnowledgeIndexGeneration record = toKnowledgeIndexGenerationRecord(generation);
        knowledgeIndexGenerationDao.insert(record);
        return toKnowledgeIndexGenerationEntity(record);
    }

    @Override
    public Optional<KnowledgeIndexGenerationEntity> findByGenerationId(String generationId) {
        return Optional.ofNullable(knowledgeIndexGenerationDao.queryByGenerationId(generationId)).map(this::toKnowledgeIndexGenerationEntity);
    }

    @Override
    public boolean attachChunkArtifactIfOwned(String generationId, String workerToken, String artifactKey,
                                              String artifactChecksum, int chunkCount, LocalDateTime now) {
        return knowledgeIndexGenerationDao.attachChunkArtifactIfOwned(generationId, workerToken, artifactKey,
                artifactChecksum, chunkCount, now) == 1;
    }

    @Override
    public boolean markReadyIfOwned(String generationId, String workerToken, Long embeddingConfigId, LocalDateTime now) {
        return knowledgeIndexGenerationDao.markReadyIfOwned(generationId, workerToken, embeddingConfigId, now) == 1;
    }

    @Override
    public boolean markFailedIfOwned(String generationId, String workerToken) {
        return knowledgeIndexGenerationDao.markFailedIfOwned(generationId, workerToken) == 1;
    }

    private KnowledgeIndexGeneration toKnowledgeIndexGenerationRecord(KnowledgeIndexGenerationEntity entity) {
        return KnowledgeIndexGeneration.builder()
                .id(entity.getId()).generationId(entity.getGenerationId()).jobId(entity.getJobId())
                .workspaceId(entity.getWorkspaceId()).documentId(entity.getDocumentId()).versionNo(entity.getVersionNo())
                .indexInputRevision(entity.getIndexInputRevision()).embeddingConfigId(entity.getEmbeddingConfigId())
                .chunkingConfigHash(entity.getChunkingConfigHash()).chunkArtifactKey(entity.getChunkArtifactKey())
                .chunkArtifactChecksum(entity.getChunkArtifactChecksum()).chunkCount(entity.getChunkCount())
                .status(entity.getStatus()).workerToken(entity.getWorkerToken()).createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt()).build();
    }

    private KnowledgeIndexGenerationEntity toKnowledgeIndexGenerationEntity(KnowledgeIndexGeneration po) {
        return KnowledgeIndexGenerationEntity.builder()
                .id(po.getId()).generationId(po.getGenerationId()).jobId(po.getJobId())
                .workspaceId(po.getWorkspaceId()).documentId(po.getDocumentId()).versionNo(po.getVersionNo())
                .indexInputRevision(po.getIndexInputRevision()).embeddingConfigId(po.getEmbeddingConfigId())
                .chunkingConfigHash(po.getChunkingConfigHash()).chunkArtifactKey(po.getChunkArtifactKey())
                .chunkArtifactChecksum(po.getChunkArtifactChecksum()).chunkCount(po.getChunkCount())
                .status(po.getStatus()).workerToken(po.getWorkerToken()).createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt()).build();
    }
}
