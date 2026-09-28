package com.hector.infrastructure.rag;

import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.knowledge.adapter.port.IGenerationVectorStore;
import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.repository.IEmbeddingConfigRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexGenerationRepository;
import com.hector.domain.knowledge.service.IKnowledgeGenerationCleanupService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** 保守清理已失效 Generation：先 CAS 领取，再删除该 Generation 独占的向量和 Chunk Artifact。 */
@Component
public class KnowledgeGenerationCleanupService implements IKnowledgeGenerationCleanupService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeGenerationCleanupService.class);

    @Resource
    private IKnowledgeIndexGenerationRepository generationRepository;

    @Resource
    private IEmbeddingConfigRepository embeddingConfigRepository;

    @Resource
    private IGenerationVectorStore vectorStore;

    @Resource
    private IDocumentStorageRepository storageRepository;

    /** 失败在配置快照落库前发生时，用于回收残留 Point 的 RAG V1 Collection。 */
    @Value("${hexworkspace.knowledge.embedding.collection:hex_workspace_rag_v1}")
    private String defaultCollection;

    @Override
    public int cleanExpired(LocalDateTime expiredBefore, int limit, int leaseMinutes) {
        int cleaned = 0;
        LocalDateTime now = utcNow();
        for (KnowledgeIndexGenerationEntity generation : generationRepository.listCleanupCandidates(
                expiredBefore, now, limit)) {
            String token = UUID.randomUUID().toString();
            if (!generationRepository.claimForCleanup(
                    generation.getGenerationId(), token, expiredBefore, now.plusMinutes(leaseMinutes), now)) {
                continue;
            }
            try {
                String collectionName = generationCollection(generation);
                vectorStore.deleteByGeneration(collectionName, generation.getGenerationId());
                if (generation.getChunkArtifactKey() != null) storageRepository.delete(generation.getChunkArtifactKey());
                if (generationRepository.markCleanedIfOwned(generation.getGenerationId(), token, utcNow())) cleaned++;
            } catch (Exception exception) {
                generationRepository.releaseCleanupClaim(generation.getGenerationId(), token, utcNow());
                log.error("Generation 清理失败 generationId:{}", generation.getGenerationId(), exception);
            }
        }
        return cleaned;
    }

    /** MySQL DATETIME 按 UTC 约定保存和比较 Cleanup 的时间字段。 */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String generationCollection(KnowledgeIndexGenerationEntity generation) {
        if (generation.getEmbeddingConfigId() == null) {
            return defaultCollection;
        }
        EmbeddingConfigEntity config = embeddingConfigRepository.findById(generation.getEmbeddingConfigId())
                .orElseThrow(() -> new IllegalStateException("Generation 缺少 Embedding 配置"));
        return config.getCollectionName();
    }
}
