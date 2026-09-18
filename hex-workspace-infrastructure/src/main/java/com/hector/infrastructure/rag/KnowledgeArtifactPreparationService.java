package com.hector.infrastructure.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.knowledge.adapter.port.IDocumentLoader;
import com.hector.domain.knowledge.adapter.port.IParsedDocumentChunker;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexJobEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeParseArtifactEntity;
import com.hector.domain.knowledge.model.valobj.KnowledgeChunkVO;
import com.hector.domain.knowledge.model.valobj.LoadedDocumentVO;
import com.hector.domain.knowledge.model.valobj.ParsedDocumentVO;
import com.hector.domain.knowledge.repository.IKnowledgeIndexGenerationRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexJobRepository;
import com.hector.domain.knowledge.repository.IKnowledgeParseArtifactRepository;
import com.hector.infrastructure.rag.parser.DocumentParserSelector;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Phase 2 的最小 Worker 编排：生成解析产物和 Chunk 产物，但不发布可检索索引。
 */
@Component
public class KnowledgeArtifactPreparationService {

    private static final int BUILDING = 0;
    private static final int READY = 1;

    @Resource
    private IKnowledgeIndexJobRepository knowledgeIndexJobRepository;

    @Resource
    private IKnowledgeIndexGenerationRepository knowledgeIndexGenerationRepository;

    @Resource
    private IKnowledgeParseArtifactRepository knowledgeParseArtifactRepository;

    @Resource
    private IDocumentRepository documentRepository;

    @Resource
    private IDocumentLoader documentLoader;

    @Resource
    private DocumentParserSelector documentParserSelector;

    @Resource
    private IParsedDocumentChunker parsedDocumentChunker;

    @Resource
    private IDocumentStorageRepository documentStorageRepository;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 仅允许持有当前任务租约的 Worker 执行准备流程。
     *
     * @param jobCode 索引任务编码
     * @param workerToken 抢占任务时写入的执行令牌
     */
    public String prepare(String jobCode, String workerToken) {
        // 1. 重新读取任务，验证 Worker 仍持有有效租约，避免过期执行者写入后续产物。
        KnowledgeIndexJobEntity job = knowledgeIndexJobRepository.findByJobCode(jobCode)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        assertWorkerOwnership(job, workerToken);
        if (!isCurrentIndexInput(job)) {
            knowledgeIndexJobRepository.markSkippedIfOwned(jobCode, workerToken, "文档版本或索引修订已过期");
            return null;
        }

        // 2. 精确加载不可变版本，解析并将可复用的解析结果写入对象存储与元数据表。
        LoadedDocumentVO loadedDocument = documentLoader.load(job.getWorkspaceId(), job.getDocumentId(), job.getVersionNo());
        ParsedDocumentVO parsedDocument = documentParserSelector.parse(loadedDocument);
        persistParseArtifact(loadedDocument, parsedDocument);

        // 3. 为本次执行创建独立 Generation，生成 Chunk 产物；保持 BUILDING，等待 Phase 3 向量构建。
        String generationId = UUID.randomUUID().toString().replace("-", "");
        KnowledgeIndexGenerationEntity generation = knowledgeIndexGenerationRepository.save(KnowledgeIndexGenerationEntity.builder()
                .generationId(generationId).jobId(job.getId()).workspaceId(job.getWorkspaceId())
                .documentId(job.getDocumentId()).versionNo(job.getVersionNo()).indexInputRevision(job.getIndexInputRevision())
                .chunkingConfigHash("structure-aware-v1").status(BUILDING).workerToken(workerToken)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        List<KnowledgeChunkVO> chunks = parsedDocumentChunker.chunk(generation.getGenerationId(), job.getDocumentId(), job.getVersionNo(), parsedDocument);
        persistChunkArtifact(generation, workerToken, chunks);
        return generation.getGenerationId();
    }

    private void persistParseArtifact(LoadedDocumentVO document, ParsedDocumentVO parsedDocument) {
        String artifactKey = "knowledge/parsed/%d/%d/%s/%s.json".formatted(document.documentId(), document.versionNo(),
                parsedDocument.parserVersion(), document.sourceChecksum());
        try {
            byte[] payload = objectMapper.writeValueAsBytes(parsedDocument);
            String checksum = checksum(payload);
            KnowledgeParseArtifactEntity artifact = knowledgeParseArtifactRepository.findByIdentity(document.documentId(),
                    document.versionNo(), document.sourceChecksum(), parsedDocument.parserVersion(), parsedDocument.schemaVersion())
                    .orElseGet(() -> {
                        documentStorageRepository.upload(artifactKey, payload, "application/json");
                        return knowledgeParseArtifactRepository.saveIfAbsent(KnowledgeParseArtifactEntity.builder()
                                .documentId(document.documentId()).versionNo(document.versionNo())
                                .sourceChecksum(document.sourceChecksum()).parserVersion(parsedDocument.parserVersion())
                                .schemaVersion(parsedDocument.schemaVersion()).artifactKey(artifactKey)
                                .artifactChecksum(checksum).status(READY).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
                    });
            verifyStoredArtifact(artifact);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
    }

    /**
     * 复用解析产物前回读对象存储验证摘要，避免仅因元数据存在就引用丢失或被篡改的对象。
     */
    private void verifyStoredArtifact(KnowledgeParseArtifactEntity artifact) {
        if (artifact.getStatus() != READY || artifact.getArtifactKey() == null || artifact.getArtifactChecksum() == null) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
        byte[] content = documentStorageRepository.download(artifact.getArtifactKey());
        if (!checksum(content).equalsIgnoreCase(artifact.getArtifactChecksum())) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
    }

    private void persistChunkArtifact(KnowledgeIndexGenerationEntity generation, String workerToken, List<KnowledgeChunkVO> chunks) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(chunks);
            String artifactKey = "knowledge/chunks/%s.json".formatted(generation.getGenerationId());
            documentStorageRepository.upload(artifactKey, payload, "application/json");
            if (!knowledgeIndexGenerationRepository.attachChunkArtifactIfOwned(generation.getGenerationId(), workerToken,
                    artifactKey, checksum(payload), chunks.size(), LocalDateTime.now())) {
                throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
    }

    private void assertWorkerOwnership(KnowledgeIndexJobEntity job, String workerToken) {
        if (job.getWorkerToken() == null || !job.getWorkerToken().equals(workerToken)
                || job.getRunningLeaseUntil() == null || !job.getRunningLeaseUntil().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
    }

    private boolean isCurrentIndexInput(KnowledgeIndexJobEntity job) {
        DocumentEntity document = documentRepository.findById(job.getDocumentId())
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        return job.getWorkspaceId().equals(document.getWorkspaceId())
                && job.getVersionNo().equals(document.getCurrentVersionNo())
                && job.getIndexInputRevision().equals(document.getIndexInputRevision());
    }

    private String checksum(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("知识产物摘要计算失败", exception);
        }
    }
}
