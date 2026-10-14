package com.hector.domain.knowledge.service;

import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationReadinessVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationResultVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationChunkVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationRerankResultVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationRerankInputVO;

import java.util.List;

/**
 * 供受控离线评测调用的当前检索链路适配服务。
 * 评测调用方不提供用户身份，服务仅使用 Workspace 权威 owner 建立 RetrievalContext。
 */
public interface IRetrievalEvaluationService {
    /** 受控评测：TopK Chunk，每文档额度等于总 TopK，不产生额外文档截断。 */
    default RetrievalEvaluationResultVO retrieveChunkTopK(Long workspaceId, String query, Integer candidateLimit, Integer topK) {
        throw new UnsupportedOperationException("不支持 Chunk TopK 评测");
    }
    /** 固定每文档两条，仅在受控 A/B 中逐请求启用/禁用 BM25/RRF。 */
    default RetrievalEvaluationResultVO retrieveAB(Long workspaceId, String query, Integer candidateLimit, Integer topK,
            boolean hybrid) { throw new UnsupportedOperationException("不支持 A/B 评测"); }

    RetrievalEvaluationResultVO retrieve(Long workspaceId, String query, Integer candidateLimit, Integer topK);

    /** C2 读取 Dense 候选时跳过 Rerank，融合完成后由独立 rerankCandidates 执行唯一一次重排。 */
    RetrievalEvaluationResultVO retrieveCandidates(Long workspaceId, String query, Integer candidateLimit, Integer topK);

    List<RetrievalEvaluationReadinessVO> readiness(Long workspaceId, List<String> documentCodes);

    /**
     * 仅供离线评测将冻结 evidence quote 映射到当前已发布 Generation 的运行时 Chunk。
     */
    List<RetrievalEvaluationChunkVO> activeChunks(Long workspaceId, List<String> documentCodes);

    /**
     * C2 等离线实验使用：只接受当前 Active Generation 中可验证的 Chunk identity，
     * 再调用当前配置的 Reranker。不会改变生产检索的候选生成路径。
     */
    RetrievalEvaluationRerankResultVO rerankCandidates(
            Long workspaceId, String query, List<RetrievalEvaluationCandidateVO> candidates, Integer topK,
            RetrievalEvaluationRerankInputVO inputVariant);
}
