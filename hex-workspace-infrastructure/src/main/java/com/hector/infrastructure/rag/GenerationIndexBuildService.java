package com.hector.infrastructure.rag;

import com.alibaba.fastjson.JSON;
import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.knowledge.adapter.port.IEmbeddingClient;
import com.hector.domain.knowledge.adapter.port.IGenerationVectorStore;
import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.model.valobj.EmbeddingVectorVO;
import com.hector.domain.knowledge.model.valobj.GenerationVectorPointVO;
import com.hector.domain.knowledge.model.valobj.KnowledgeChunkVO;
import com.hector.domain.knowledge.repository.IEmbeddingConfigRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexGenerationRepository;
import com.hector.domain.knowledge.repository.IServingVectorSpaceRepository;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 将已完成 Phase 2 的 Chunk Artifact 构建为隔离的 Generation 向量。 */
@Component
public class GenerationIndexBuildService {
    private static final Logger log = LoggerFactory.getLogger(GenerationIndexBuildService.class);

    @Resource
    private IKnowledgeIndexGenerationRepository generationRepository;

    @Resource
    private IEmbeddingConfigRepository embeddingConfigRepository;

    @Resource
    private IDocumentRepository documentRepository;

    @Resource
    private IDocumentStorageRepository storageRepository;

    @Resource
    private IEmbeddingClient embeddingClient;

    @Resource
    private IGenerationVectorStore vectorStore;

    @Resource
    private IServingVectorSpaceRepository servingVectorSpaceRepository;

    @Value("${hexworkspace.knowledge.embedding.provider:DashScope}")
    private String provider;

    @Value("${ai.embedding.dashscope.model:text-embedding-v4}")
    private String model;

    @Value("${ai.embedding.dashscope.dimensions:1024}")
    private Integer dimension;

    @Value("${hexworkspace.knowledge.embedding.distance:COSINE}")
    private String distance;

    @Value("${hexworkspace.knowledge.embedding.collection:hex_workspace_rag_v1}")
    private String collection;

    @Value("${hexworkspace.knowledge.embedding.batch-size:16}")
    private int batchSize;

    /** 构建指定 Generation；外部调用前后均由 Worker 校验 Job 租约。 */
    public void build(String generationId, String workerToken) {
        log.info("Generation 向量构建开始 generationId:{}", generationId);
        KnowledgeIndexGenerationEntity generation = generationRepository.findByGenerationId(generationId)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED));
        if (!workerToken.equals(generation.getWorkerToken()) || generation.getStatus() != 0) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
        DocumentEntity document = documentRepository.findById(generation.getDocumentId())
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        List<KnowledgeChunkVO> chunks = loadChunks(generation);
        EmbeddingConfigEntity config = embeddingConfigRepository.saveIfAbsent(config());
        servingVectorSpaceRepository.initializeIfAbsent(config.getId());
        vectorStore.ensureCollection(config.getDimension(), config.getDistance(), config.getCollectionName());

        int effectiveBatchSize = Math.max(1, batchSize);
        for (int offset = 0; offset < chunks.size(); offset += effectiveBatchSize) {
            List<KnowledgeChunkVO> batch = chunks.subList(offset, Math.min(chunks.size(), offset + effectiveBatchSize));
            List<EmbeddingVectorVO> vectors = embeddingClient.embedIndexed(
                            batch.stream().map(chunk -> embeddingInput(document.getTitle(), chunk)).toList())
                    .stream()
                    .sorted(Comparator.comparingInt(EmbeddingVectorVO::index))
                    .toList();
            validateVectors(vectors, batch.size(), config.getDimension());

            List<GenerationVectorPointVO> points = new ArrayList<>();
            for (int index = 0; index < batch.size(); index++) {
                points.add(point(document, generation, batch.get(index), vectors.get(index).vector(), config.getId()));
            }
            vectorStore.upsert(config.getCollectionName(), points);
        }
        vectorStore.verifyGenerationPoints(
                config.getCollectionName(),
                generationId,
                chunks.stream().map(chunk -> pointId(generationId, chunk.chunkNo())).toList());
        if (!generationRepository.markReadyIfOwned(generationId, workerToken, config.getId(), LocalDateTime.now(ZoneOffset.UTC))) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
        log.info("Generation 向量构建完成 generationId:{} chunkCount:{}", generationId, chunks.size());
    }

    /** Worker 在外部构建失败时仅把自己创建的 BUILDING Generation 标记为失败。 */
    public void markFailed(String generationId, String workerToken) {
        generationRepository.markFailedIfOwned(generationId, workerToken, LocalDateTime.now(ZoneOffset.UTC));
    }

    private List<KnowledgeChunkVO> loadChunks(KnowledgeIndexGenerationEntity generation) {
        try {
            byte[] payload = storageRepository.download(generation.getChunkArtifactKey());
            if (!sha256(payload).equalsIgnoreCase(generation.getChunkArtifactChecksum())) {
                throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
            }
            List<KnowledgeChunkVO> chunks = JSON.parseArray(new String(payload, StandardCharsets.UTF_8), KnowledgeChunkVO.class);
            if (chunks.size() != generation.getChunkCount()) {
                throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
            }
            return chunks;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
    }

    private EmbeddingConfigEntity config() {
        String source = provider + "|" + model + "|" + dimension + "|" + distance + "|" + collection;
        return EmbeddingConfigEntity.builder()
                .configHash(sha256(source.getBytes(StandardCharsets.UTF_8)))
                .provider(provider)
                .model(model)
                .dimension(dimension)
                .distance(distance)
                .collectionName(collection)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private String embeddingInput(String title, KnowledgeChunkVO chunk) {
        return "标题：" + title + "\n路径：" + String.join(" / ", chunk.headingPath()) + "\n正文：" + chunk.text();
    }

    private void validateVectors(List<EmbeddingVectorVO> vectors, int expected, int expectedDimension) {
        if (vectors.size() != expected) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
        for (int index = 0; index < expected; index++) {
            EmbeddingVectorVO vector = vectors.get(index);
            if (vector.index() != index || vector.vector() == null || vector.vector().size() != expectedDimension
                    || vector.vector().stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
                throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
            }
        }
    }

    private GenerationVectorPointVO point(DocumentEntity document, KnowledgeIndexGenerationEntity generation, KnowledgeChunkVO chunk, List<Float> vector, Long configId) {
        String pointId = pointId(generation.getGenerationId(), chunk.chunkNo());
        Map<String, Object> payload = new HashMap<>();
        payload.put("workspace_id", generation.getWorkspaceId());
        payload.put("document_id", generation.getDocumentId());
        payload.put("version_no", generation.getVersionNo());
        payload.put("index_input_revision", generation.getIndexInputRevision());
        payload.put("generation_id", generation.getGenerationId());
        payload.put("chunk_no", chunk.chunkNo());
        payload.put("document_code", document.getDocumentCode());
        payload.put("heading_path", chunk.headingPath());
        payload.put("body", chunk.text());
        payload.put("page_start", chunk.pageStart());
        payload.put("page_end", chunk.pageEnd());
        payload.put("content_hash", chunk.contentHash());
        payload.put("embedding_config_id", configId);
        return new GenerationVectorPointVO(pointId, vector, payload);
    }

    private String pointId(String generationId, int chunkNo) {
        return UUID.nameUUIDFromBytes((generationId + ":" + chunkNo).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String sha256(byte[] source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source);
            StringBuilder result = new StringBuilder();
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Generation 产物摘要计算失败", exception);
        }
    }
}
