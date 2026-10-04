package com.hector.domain.knowledge.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexStateEntity;
import com.hector.domain.knowledge.model.valobj.RetrievalContextVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationReadinessVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationDiagnosticsVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationResultVO;
import com.hector.domain.knowledge.model.valobj.RetrievalOptionsVO;
import com.hector.domain.knowledge.repository.IKnowledgeIndexGenerationRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexStateRepository;
import com.hector.domain.knowledge.service.IKnowledgeRetrievalService;
import com.hector.domain.knowledge.service.IRetrievalEvaluationService;
import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

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
    private final IKnowledgeRetrievalService retrievalService;

    public RetrievalEvaluationServiceImpl(
            IWorkspaceRepository workspaceRepository,
            IDocumentRepository documentRepository,
            IDocumentVersionRepository documentVersionRepository,
            IKnowledgeIndexStateRepository indexStateRepository,
            IKnowledgeIndexGenerationRepository generationRepository,
            IKnowledgeRetrievalService retrievalService) {
        this.workspaceRepository = workspaceRepository;
        this.documentRepository = documentRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.indexStateRepository = indexStateRepository;
        this.generationRepository = generationRepository;
        this.retrievalService = retrievalService;
    }

    @Override
    public RetrievalEvaluationResultVO retrieve(Long workspaceId, String query, Integer candidateLimit, Integer topK) {
        WorkspaceEntity workspace = requireActiveWorkspace(workspaceId);
        var execution = retrievalService.retrieveWithEvaluationTrace(
                new RetrievalContextVO(workspace.getOwnerUserId(), workspace.getId()),
                query,
                new RetrievalOptionsVO(candidateLimit, null, topK));
        var documentCodes = documentRepository.listByWorkspaceId(workspace.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(DocumentEntity::getId, DocumentEntity::getDocumentCode));
        return new RetrievalEvaluationResultVO(execution.result(), execution.timing(),
                new RetrievalEvaluationDiagnosticsVO(
                        toEvaluationCandidates(execution.diagnostics().qdrantCandidates(), documentCodes),
                        toEvaluationCandidates(execution.diagnostics().authorityValidCandidates(), documentCodes),
                        toEvaluationCandidates(execution.diagnostics().rerankedCandidates(), documentCodes),
                        toEvaluationCandidates(execution.diagnostics().finalCandidates(), documentCodes)));
    }

    @Override
    public List<RetrievalEvaluationReadinessVO> readiness(Long workspaceId, List<String> documentCodes) {
        requireActiveWorkspace(workspaceId);
        if (documentCodes == null || documentCodes.isEmpty()) {
            throw new IllegalArgumentException("documentCodes 不能为空");
        }
        return documentCodes.stream().map(code -> readinessOf(workspaceId, code)).toList();
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

    private List<RetrievalEvaluationCandidateVO> toEvaluationCandidates(
            List<com.hector.domain.knowledge.model.valobj.RetrievalCandidateRankVO> candidates,
            java.util.Map<Long, String> documentCodes) {
        return candidates.stream()
                .map(candidate -> new RetrievalEvaluationCandidateVO(
                        documentCodes.get(candidate.documentId()), candidate.rank(), candidate.score()))
                .filter(candidate -> candidate.documentCode() != null && !candidate.documentCode().isBlank())
                .toList();
    }
}
