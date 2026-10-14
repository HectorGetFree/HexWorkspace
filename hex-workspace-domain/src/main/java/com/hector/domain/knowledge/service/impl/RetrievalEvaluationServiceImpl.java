package com.hector.domain.knowledge.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexStateEntity;
import com.hector.domain.knowledge.model.valobj.RetrievalContextVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationReadinessVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationChunkVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationDiagnosticsVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationRerankResultVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationRerankInputVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationResultVO;
import com.hector.domain.knowledge.model.valobj.RetrievalOptionsVO;
import com.hector.domain.knowledge.model.valobj.RetrievalRerankCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalRerankScoreVO;
import com.hector.domain.knowledge.adapter.port.IRetrievalReranker;
import com.hector.domain.knowledge.repository.IKnowledgeIndexGenerationRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexStateRepository;
import com.hector.domain.knowledge.service.IKnowledgeRetrievalService;
import com.hector.domain.knowledge.service.IRetrievalEvaluationService;
import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.hector.domain.knowledge.model.valobj.KnowledgeChunkVO;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 评测入口复用生产可信检索服务，并以当前 Active Generation 作为语料就绪的唯一依据。
 */
@Service
public class RetrievalEvaluationServiceImpl implements IRetrievalEvaluationService {

    private static final int GENERATION_READY = 1;

    private final IWorkspaceRepository workspaceRepository;
    private final IDocumentRepository documentRepository;
    private final IDocumentVersionRepository documentVersionRepository;
    private final IKnowledgeIndexStateRepository indexStateRepository;
    private final IKnowledgeIndexGenerationRepository generationRepository;
    private final IDocumentStorageRepository documentStorageRepository;
    private final IKnowledgeRetrievalService retrievalService;
    private final IRetrievalReranker retrievalReranker;

    public RetrievalEvaluationServiceImpl(
            IWorkspaceRepository workspaceRepository,
            IDocumentRepository documentRepository,
            IDocumentVersionRepository documentVersionRepository,
            IKnowledgeIndexStateRepository indexStateRepository,
            IKnowledgeIndexGenerationRepository generationRepository,
            IDocumentStorageRepository documentStorageRepository,
            IKnowledgeRetrievalService retrievalService,
            IRetrievalReranker retrievalReranker) {
        this.workspaceRepository = workspaceRepository;
        this.documentRepository = documentRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.indexStateRepository = indexStateRepository;
        this.generationRepository = generationRepository;
        this.documentStorageRepository = documentStorageRepository;
        this.retrievalService = retrievalService;
        this.retrievalReranker = retrievalReranker;
    }

    @Override
    public RetrievalEvaluationResultVO retrieve(Long workspaceId, String query, Integer candidateLimit, Integer topK) {
        return retrieve(workspaceId, query, candidateLimit, topK, false);
    }

    @Override
    public RetrievalEvaluationResultVO retrieveCandidates(Long workspaceId, String query, Integer candidateLimit, Integer topK) {
        return retrieve(workspaceId, query, candidateLimit, topK, true);
    }

    private RetrievalEvaluationResultVO retrieve(
            Long workspaceId, String query, Integer candidateLimit, Integer topK, boolean candidatesOnly) {
        WorkspaceEntity workspace = requireActiveWorkspace(workspaceId);
        var context = new RetrievalContextVO(workspace.getOwnerUserId(), workspace.getId());
        var execution = candidatesOnly
                ? retrievalService.retrieveCandidatesWithEvaluationTrace(
                        context, query, new RetrievalOptionsVO(candidateLimit, null, topK))
                : retrievalService.retrieveWithEvaluationTrace(
                        context, query, new RetrievalOptionsVO(candidateLimit, null, topK));
        return evaluationResult(workspace, execution);
    }

    @Override
    public RetrievalEvaluationResultVO retrieveAB(Long workspaceId, String query, Integer candidateLimit, Integer topK,
            boolean hybrid) {
        WorkspaceEntity workspace = requireActiveWorkspace(workspaceId);
        var execution = retrievalService.retrieveWithEvaluationProfile(
                new RetrievalContextVO(workspace.getOwnerUserId(), workspaceId), query,
                new RetrievalOptionsVO(candidateLimit, 2, topK), hybrid);
        return evaluationResult(workspace, execution);
    }

    private RetrievalEvaluationResultVO evaluationResult(WorkspaceEntity workspace,
            com.hector.domain.knowledge.model.valobj.RetrievalExecutionVO execution) {
        var documentCodes = documentRepository.listByWorkspaceId(workspace.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(DocumentEntity::getId, DocumentEntity::getDocumentCode));
        return new RetrievalEvaluationResultVO(execution.result(), execution.timing(),
                new RetrievalEvaluationDiagnosticsVO(
                        toEvaluationCandidates(execution.diagnostics().qdrantCandidates(), documentCodes),
                        toEvaluationCandidates(execution.diagnostics().authorityValidCandidates(), documentCodes),
                        toEvaluationCandidates(execution.diagnostics().rerankedCandidates(), documentCodes),
                        toEvaluationCandidates(execution.diagnostics().finalCandidates(), documentCodes),
                        toEvaluationCandidates(execution.diagnostics().sparseCandidates(), documentCodes),
                        toEvaluationCandidates(execution.diagnostics().fusedCandidates(), documentCodes),
                        toEvaluationCandidates(execution.diagnostics().rerankedChunks(), documentCodes)));
    }

    @Override
    public RetrievalEvaluationResultVO retrieveChunkTopK(Long workspaceId, String query, Integer candidateLimit, Integer topK) {
        if (!Integer.valueOf(30).equals(candidateLimit) || !Integer.valueOf(5).equals(topK))
            throw new IllegalArgumentException("本次冻结评测仅允许 Top30 候选 / Top5 Chunk");
        WorkspaceEntity workspace = requireActiveWorkspace(workspaceId);
        return evaluationResult(workspace, retrievalService.retrieveWithEvaluationProfile(
                new RetrievalContextVO(workspace.getOwnerUserId(), workspaceId), query,
                new RetrievalOptionsVO(30, 5, 5), true));
    }

    @Override
    public List<RetrievalEvaluationReadinessVO> readiness(Long workspaceId, List<String> documentCodes) {
        requireActiveWorkspace(workspaceId);
        if (documentCodes == null || documentCodes.isEmpty()) {
            throw new IllegalArgumentException("documentCodes 不能为空");
        }
        return documentCodes.stream().map(code -> readinessOf(workspaceId, code)).toList();
    }

    @Override
    public List<RetrievalEvaluationChunkVO> activeChunks(Long workspaceId, List<String> documentCodes) {
        requireActiveWorkspace(workspaceId);
        if (documentCodes == null || documentCodes.isEmpty()) {
            throw new IllegalArgumentException("documentCodes 不能为空");
        }
        return documentCodes.stream().flatMap(documentCode -> {
            RetrievalEvaluationReadinessVO readiness = readinessOf(workspaceId, documentCode);
            if (!readiness.ready() || readiness.generationId() == null) {
                throw new IllegalStateException("评测文档尚未进入当前 Active Generation: " + documentCode);
            }
            KnowledgeIndexGenerationEntity generation = generationRepository.findByGenerationId(readiness.generationId())
                    .orElseThrow(() -> new IllegalStateException("Active Generation 不存在: " + readiness.generationId()));
            return loadActiveChunks(documentCode, generation).stream();
        }).toList();
    }

    @Override
    public RetrievalEvaluationRerankResultVO rerankCandidates(
            Long workspaceId, String query, List<RetrievalEvaluationCandidateVO> candidates, Integer topK,
            RetrievalEvaluationRerankInputVO inputVariant) {
        WorkspaceEntity workspace = requireActiveWorkspace(workspaceId);
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (candidates == null || candidates.size() < 2 || candidates.size() > 100) {
            throw new IllegalArgumentException("评测 rerank candidates 数量必须为 2..100");
        }
        int resultLimit = topK == null ? 5 : topK;
        if (resultLimit < 1 || resultLimit > 100) {
            throw new IllegalArgumentException("topK 必须为 1..100");
        }
        if (retrievalReranker == null || !retrievalReranker.isEnabled()) {
            throw new IllegalStateException("当前 Reranker 未启用，不能产生可比的 C2 结果");
        }
        RetrievalEvaluationRerankInputVO effectiveInputVariant = inputVariant == null
                ? RetrievalEvaluationRerankInputVO.STRUCTURAL_CONTEXT : inputVariant;

        Map<String, DocumentEntity> documents = documentRepository.listByWorkspaceId(workspace.getId()).stream()
                .collect(Collectors.toMap(DocumentEntity::getDocumentCode, document -> document));
        List<String> documentCodes = candidates.stream().map(RetrievalEvaluationCandidateVO::documentCode)
                .filter(code -> code != null && !code.isBlank()).distinct().toList();
        Map<String, RetrievalEvaluationChunkVO> activeChunks = activeChunks(workspace.getId(), documentCodes).stream()
                .collect(Collectors.toMap(this::chunkKey, chunk -> chunk, (first, ignored) -> first));
        Map<String, RetrievalEvaluationCandidateVO> distinctCandidates = new LinkedHashMap<>();
        for (RetrievalEvaluationCandidateVO candidate : candidates) {
            if (candidate == null || candidate.documentCode() == null || candidate.documentCode().isBlank()
                    || candidate.generationId() == null || candidate.generationId().isBlank()
                    || candidate.chunkNo() == null || candidate.chunkNo() < 0 || candidate.rank() == null
                    || candidate.rank() < 1 || candidate.score() == null || !Double.isFinite(candidate.score())) {
                throw new IllegalArgumentException("评测 rerank candidate identity 非法");
            }
            if (documents.get(candidate.documentCode()) == null) {
                throw new IllegalArgumentException("评测 rerank candidate 不属于当前 Workspace");
            }
            String key = chunkKey(candidate);
            if (!activeChunks.containsKey(key)) {
                throw new IllegalArgumentException("评测 rerank candidate 不属于当前 Active Generation");
            }
            if (distinctCandidates.putIfAbsent(key, candidate) != null) {
                throw new IllegalArgumentException("评测 rerank candidate 重复");
            }
        }

        List<RetrievalEvaluationCandidateVO> ordered = distinctCandidates.values().stream()
                .sorted(Comparator.comparing(RetrievalEvaluationCandidateVO::rank)
                        .thenComparing(RetrievalEvaluationCandidateVO::documentCode)
                        .thenComparing(RetrievalEvaluationCandidateVO::chunkNo))
                .toList();
        List<RetrievalRerankCandidateVO> rerankInput = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            RetrievalEvaluationCandidateVO candidate = ordered.get(index);
            RetrievalEvaluationChunkVO chunk = activeChunks.get(chunkKey(candidate));
            rerankInput.add(new RetrievalRerankCandidateVO(index,
                    rerankText(documents.get(candidate.documentCode()), chunk, effectiveInputVariant)));
        }
        List<RetrievalRerankScoreVO> scores = retrievalReranker.rerank(query.trim(), rerankInput);
        Map<Integer, Double> scoresByIndex = validateRerankScores(scores, ordered.size());
        List<Integer> indexes = new ArrayList<>(scoresByIndex.keySet());
        indexes.sort(Comparator.<Integer, Double>comparing(scoresByIndex::get, Comparator.reverseOrder())
                .thenComparing(index -> ordered.get(index).score(), Comparator.reverseOrder())
                .thenComparingInt(Integer::intValue));
        List<RetrievalEvaluationCandidateVO> result = new ArrayList<>();
        for (int rank = 0; rank < indexes.size() && result.size() < resultLimit; rank++) {
            int index = indexes.get(rank);
            RetrievalEvaluationCandidateVO candidate = ordered.get(index);
            result.add(new RetrievalEvaluationCandidateVO(candidate.documentCode(), candidate.generationId(),
                    candidate.chunkNo(), rank + 1, scoresByIndex.get(index)));
        }
        return new RetrievalEvaluationRerankResultVO(result, true);
    }

    private RetrievalEvaluationReadinessVO readinessOf(Long workspaceId, String documentCode) {
        if (documentCode == null || documentCode.isBlank()) {
            return new RetrievalEvaluationReadinessVO(documentCode, null, null, null, false, "DOCUMENT_CODE_INVALID");
        }
        DocumentEntity document = documentRepository.findByDocumentCode(documentCode).orElse(null);
        if (document == null || !Objects.equals(document.getWorkspaceId(), workspaceId)) {
            return new RetrievalEvaluationReadinessVO(documentCode, null, null, null, false, "DOCUMENT_NOT_FOUND");
        }
        if (!document.isActive() || document.getCurrentVersionNo() == null || document.getIndexInputRevision() == null) {
            return new RetrievalEvaluationReadinessVO(documentCode, document.getId(), null, null, false, "DOCUMENT_NOT_ACTIVE");
        }
        Long versionId = documentVersionRepository.findByDocumentIdAndVersionNo(document.getId(), document.getCurrentVersionNo())
                .map(version -> version.getId()).orElse(null);
        KnowledgeIndexStateEntity state = indexStateRepository.findByDocumentId(document.getId()).orElse(null);
        if (state == null || state.getActiveGenerationId() == null || state.getActiveGenerationId().isBlank()) {
            return new RetrievalEvaluationReadinessVO(documentCode, document.getId(), versionId, null, false, "ACTIVE_GENERATION_MISSING");
        }
        KnowledgeIndexGenerationEntity generation = generationRepository.findByGenerationId(state.getActiveGenerationId())
                .orElse(null);
        if (generation == null || generation.getStatus() == null || generation.getStatus() != GENERATION_READY) {
            return new RetrievalEvaluationReadinessVO(documentCode, document.getId(), versionId,
                    state.getActiveGenerationId(), false, "GENERATION_NOT_READY");
        }
        boolean identityMatches = Objects.equals(generation.getWorkspaceId(), workspaceId)
                && Objects.equals(generation.getDocumentId(), document.getId())
                && Objects.equals(generation.getVersionNo(), document.getCurrentVersionNo())
                && Objects.equals(generation.getIndexInputRevision(), document.getIndexInputRevision());
        return new RetrievalEvaluationReadinessVO(
                documentCode, document.getId(), versionId, generation.getGenerationId(), identityMatches,
                identityMatches ? "ACTIVE_GENERATION_READY" : "GENERATION_IDENTITY_MISMATCH");
    }

    private WorkspaceEntity requireActiveWorkspace(Long workspaceId) {
        if (workspaceId == null) {
            throw new IllegalArgumentException("workspaceId 不能为空");
        }
        WorkspaceEntity workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace 不存在"));
        if (!workspace.isActive() || workspace.getOwnerUserId() == null) {
            throw new IllegalStateException("Workspace 不可用于评测");
        }
        return workspace;
    }

    private List<RetrievalEvaluationChunkVO> loadActiveChunks(
            String documentCode, KnowledgeIndexGenerationEntity generation) {
        if (generation.getChunkArtifactKey() == null || generation.getChunkArtifactKey().isBlank()) {
            throw new IllegalStateException("Active Generation 缺少 Chunk Artifact: " + generation.getGenerationId());
        }
        try {
            List<KnowledgeChunkVO> chunks = JSON.parseArray(
                    new String(documentStorageRepository.download(generation.getChunkArtifactKey()), StandardCharsets.UTF_8),
                    KnowledgeChunkVO.class);
            return chunks.stream().map(chunk -> new RetrievalEvaluationChunkVO(
                    documentCode, generation.getGenerationId(), chunk.chunkNo(), chunk.text(),
                    chunk.headingPath() == null ? List.of() : List.copyOf(chunk.headingPath()), chunk.contentHash())).toList();
        } catch (Exception exception) {
            throw new IllegalStateException("读取 Active Generation Chunk Artifact 失败: " + generation.getGenerationId(), exception);
        }
    }

    private List<RetrievalEvaluationCandidateVO> toEvaluationCandidates(
            List<com.hector.domain.knowledge.model.valobj.RetrievalCandidateRankVO> candidates,
            java.util.Map<Long, String> documentCodes) {
        return candidates.stream()
                .map(candidate -> new RetrievalEvaluationCandidateVO(
                        documentCodes.get(candidate.documentId()), candidate.generationId(), candidate.chunkNo(),
                        candidate.rank(), candidate.score()))
                .filter(candidate -> candidate.documentCode() != null && !candidate.documentCode().isBlank())
                .toList();
    }

    private Map<Integer, Double> validateRerankScores(List<RetrievalRerankScoreVO> scores, int candidateCount) {
        if (scores == null || scores.size() != candidateCount) {
            throw new IllegalStateException("评测 Rerank 返回候选数不完整");
        }
        Map<Integer, Double> result = new HashMap<>();
        for (RetrievalRerankScoreVO score : scores) {
            if (score == null || score.index() == null || score.index() < 0 || score.index() >= candidateCount
                    || score.relevanceScore() == null || !Double.isFinite(score.relevanceScore())
                    || result.putIfAbsent(score.index(), score.relevanceScore()) != null) {
                throw new IllegalStateException("评测 Rerank 返回候选标识或分数无效");
            }
        }
        if (result.size() != candidateCount) {
            throw new IllegalStateException("评测 Rerank 返回候选标识不完整");
        }
        return result;
    }

    private String rerankText(
            DocumentEntity document, RetrievalEvaluationChunkVO chunk,
            RetrievalEvaluationRerankInputVO inputVariant) {
        if (inputVariant == RetrievalEvaluationRerankInputVO.RAW_CHUNK) {
            return chunk.content();
        }
        return "标题：" + Objects.toString(document.getTitle(), "")
                + "\n章节：" + String.join(" / ", chunk.headingPath())
                + "\n正文：" + chunk.content();
    }

    private String chunkKey(RetrievalEvaluationChunkVO chunk) {
        return chunk.documentCode() + ":" + chunk.generationId() + ":" + chunk.chunkNo();
    }

    private String chunkKey(RetrievalEvaluationCandidateVO candidate) {
        return candidate.documentCode() + ":" + candidate.generationId() + ":" + candidate.chunkNo();
    }
}
