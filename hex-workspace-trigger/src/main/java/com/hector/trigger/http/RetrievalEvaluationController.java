package com.hector.trigger.http;

import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationReadinessVO;
import com.hector.domain.knowledge.model.valobj.RetrievalHitVO;
import com.hector.domain.knowledge.service.IRetrievalEvaluationService;
import com.hector.trigger.api.dto.RetrievalEvaluationReadinessRequestDTO;
import com.hector.trigger.api.dto.RetrievalEvaluationRequestDTO;
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

/**
 * 离线 RAG 评测专用内部接口。
 * 该接口仅复用当前 Generation 检索链路，不承载用户请求或 Agent Capability 语义。
 */
@RestController
public class RetrievalEvaluationController {

    private final IRetrievalEvaluationService evaluationService;

    @Value("${hexworkspace.rag-evaluation.token:}")
    private String evaluationToken;

    public RetrievalEvaluationController(IRetrievalEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
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

    private boolean isAuthorized(String authorization) {
        return !evaluationToken.isBlank() && authorization != null
                && MessageDigest.isEqual(("Bearer " + evaluationToken).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8));
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
