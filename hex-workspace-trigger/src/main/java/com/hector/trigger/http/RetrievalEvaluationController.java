package com.hector.trigger.http;

import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationReadinessVO;
import com.hector.domain.knowledge.model.valobj.RetrievalHitVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationChunkVO;
import com.hector.domain.knowledge.service.IRetrievalEvaluationService;
import com.hector.trigger.api.dto.RetrievalEvaluationReadinessRequestDTO;
import com.hector.trigger.api.dto.RetrievalEvaluationRequestDTO;
import com.hector.trigger.api.dto.RetrievalEvaluationRerankRequestDTO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationRerankInputVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.annotation.Resource;
import com.hector.domain.knowledge.adapter.port.IRetrievalSparseStore;
import com.hector.domain.knowledge.adapter.port.IRetrievalReranker;

/**
 * 离线 RAG 评测专用内部接口。
 * 该接口仅复用当前 Generation 检索链路，不承载用户请求或 Agent Capability 语义。
 */
@RestController
public class RetrievalEvaluationController {

    private final IRetrievalEvaluationService evaluationService;
    @Resource private IRetrievalSparseStore sparseStore;
    @Resource private IRetrievalReranker reranker;
    @Value("${hexworkspace.knowledge.rerank.rerank-weight:1.0}")
    private double rerankWeight;

    @Value("${hexworkspace.rag-evaluation.token:}")
    private String evaluationToken;

    public RetrievalEvaluationController(IRetrievalEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /** 内部就绪检查；沿用评测 Token 与 Workspace 校验，不产生真实模型调用。 */
    @PostMapping("/internal/v1/evaluation/retrieval/hybrid-status")
    public ResponseEntity<Map<String, Object>> hybridStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody RetrievalEvaluationReadinessRequestDTO request) {
        if (!isAuthorized(authorization)) return ResponseEntity.status(401).build();
        evaluationService.readiness(request.getWorkspaceId(), request.getDocumentCodes());
        Map<String, Object> status = new LinkedHashMap<>(sparseStore.prepareAndStatus(request.getWorkspaceId()));
        status.put("rerankEnabled", reranker.isEnabled());
        status.put("maxChunksPerDocument", 0);
        status.put("documentLimitPolicy", "NONE");
        status.put("rerankWeight", rerankWeight);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/internal/v1/evaluation/retrieval")
    public ResponseEntity<Map<String, Object>> retrieve(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody RetrievalEvaluationRequestDTO request) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401).build();
        }
        var result = evaluationService.retrieve(
                request.getWorkspaceId(), request.getQuery(), request.getCandidateLimit(), request.getTopK());
        return retrievalResponse(request, result);
    }

    /** 受控单机制对照；A Dense，B Dense+BM25/RRF，两侧每文档两条，完整生产复核。 */
    @PostMapping("/internal/v1/evaluation/retrieval/ab")
    public ResponseEntity<Map<String, Object>> retrieveAB(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody RetrievalEvaluationRequestDTO request) {
        if (!isAuthorized(authorization)) return ResponseEntity.status(401).build();
        if (!"A".equals(request.getVariant()) && !"B".equals(request.getVariant()))
            throw new IllegalArgumentException("variant 必须为 A 或 B");
        var result = evaluationService.retrieveAB(request.getWorkspaceId(), request.getQuery(),
                request.getCandidateLimit(), request.getTopK(), "B".equals(request.getVariant()));
        Map<String, Object> response = new LinkedHashMap<>(retrievalResponse(request, result).getBody());
        response.put("evaluationProfile", Map.of("variant", request.getVariant(), "maxChunksPerDocument", 2));
        return ResponseEntity.ok(response);
    }

    /** 仅受控 SciFact 重测使用；总 Top5 不变，每文档 5 条等价于取消单文档限额。 */
    @PostMapping("/internal/v1/evaluation/retrieval/chunks-topk")
    public ResponseEntity<Map<String, Object>> retrieveChunkTopK(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody RetrievalEvaluationRequestDTO request) {
        if (!isAuthorized(authorization)) return ResponseEntity.status(401).build();
        var result = evaluationService.retrieveChunkTopK(request.getWorkspaceId(), request.getQuery(),
                request.getCandidateLimit(), request.getTopK());
        Map<String, Object> response = new LinkedHashMap<>(retrievalResponse(request, result).getBody());
        response.put("evaluationProfile", Map.of("variant", "HYBRID_CHUNK_TOPK", "maxChunksPerDocument", 5));
        return ResponseEntity.ok(response);
    }

    /**
     * C2 只读取 Dense 候选诊断；不在此阶段调用 Rerank，避免融合前产生第二次外部重排。
     */
    @PostMapping("/internal/v1/evaluation/retrieval/candidates")
    public ResponseEntity<Map<String, Object>> retrieveCandidates(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody RetrievalEvaluationRequestDTO request) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401).build();
        }
        var result = evaluationService.retrieveCandidates(
                request.getWorkspaceId(), request.getQuery(), request.getCandidateLimit(), request.getTopK());
        return retrievalResponse(request, result);
    }

    private ResponseEntity<Map<String, Object>> retrievalResponse(
            RetrievalEvaluationRequestDTO request,
            com.hector.domain.knowledge.model.valobj.RetrievalEvaluationResultVO result) {
        return ResponseEntity.ok(Map.of(
                "workspaceId", request.getWorkspaceId(),
                "hits", result.result().hits() == null ? List.of() : result.result().hits().stream().map(this::toHit).toList(),
                "timingMs", result.timing(),
                "diagnostics", result.diagnostics()));
    }

    @PostMapping("/internal/v1/evaluation/retrieval/readiness")
    public ResponseEntity<Map<String, Object>> readiness(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody RetrievalEvaluationReadinessRequestDTO request) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401).build();
        }
        List<RetrievalEvaluationReadinessVO> documents = evaluationService.readiness(
                request.getWorkspaceId(), request.getDocumentCodes());
        return ResponseEntity.ok(Map.of("workspaceId", request.getWorkspaceId(), "documents", documents));
    }

    /**
     * 读取当前 Active Generation 的 Chunk Artifact，供离线评测绑定冻结 evidence。
     */
    @PostMapping("/internal/v1/evaluation/retrieval/chunks")
    public ResponseEntity<Map<String, Object>> chunks(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody RetrievalEvaluationReadinessRequestDTO request) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401).build();
        }
        List<RetrievalEvaluationChunkVO> chunks = evaluationService.activeChunks(
                request.getWorkspaceId(), request.getDocumentCodes());
        return ResponseEntity.ok(Map.of("workspaceId", request.getWorkspaceId(), "chunks", chunks));
    }

    /**
     * C2 等离线实验重用当前 Reranker；调用方只能提交 Active Generation 的 Chunk identity，
     * 具体正文仍由服务端从权威 Artifact 重建，不能借此向 Reranker 注入任意文本。
     */
    @PostMapping("/internal/v1/evaluation/retrieval/rerank")
    public ResponseEntity<Map<String, Object>> rerank(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody RetrievalEvaluationRerankRequestDTO request) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401).build();
        }
        List<RetrievalEvaluationCandidateVO> candidates = request.getCandidates() == null ? List.of()
                : request.getCandidates().stream().map(candidate -> new RetrievalEvaluationCandidateVO(
                        candidate.getDocumentCode(), candidate.getGenerationId(), candidate.getChunkNo(),
                        candidate.getRank(), candidate.getScore())).toList();
        RetrievalEvaluationRerankInputVO inputVariant = parseInputVariant(request.getInputVariant());
        var result = evaluationService.rerankCandidates(
                request.getWorkspaceId(), request.getQuery(), candidates, request.getTopK(), inputVariant);
        return ResponseEntity.ok(Map.of(
                "workspaceId", request.getWorkspaceId(),
                "rerankApplied", result.rerankApplied(),
                "inputVariant", inputVariant.name(),
                "candidates", result.candidates()));
    }

    private boolean isAuthorized(String authorization) {
        return !evaluationToken.isBlank() && authorization != null
                && MessageDigest.isEqual(("Bearer " + evaluationToken).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8));
    }

    private RetrievalEvaluationRerankInputVO parseInputVariant(String inputVariant) {
        if (inputVariant == null || inputVariant.isBlank()) {
            return RetrievalEvaluationRerankInputVO.STRUCTURAL_CONTEXT;
        }
        try {
            return RetrievalEvaluationRerankInputVO.valueOf(inputVariant.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("评测 rerank inputVariant 非法");
        }
    }

    private Map<String, Object> toHit(RetrievalHitVO hit) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("documentId", hit.documentId());
        response.put("documentCode", hit.documentCode());
        response.put("versionId", hit.versionId());
        response.put("versionNo", hit.versionNo());
        response.put("generationId", hit.generationId());
        response.put("chunkNo", hit.chunkNo());
        response.put("title", hit.title());
        response.put("headingPath", hit.headingPath());
        response.put("content", hit.content());
        response.put("pageStart", hit.pageStart());
        response.put("pageEnd", hit.pageEnd());
        response.put("score", hit.score());
        return response;
    }
}
