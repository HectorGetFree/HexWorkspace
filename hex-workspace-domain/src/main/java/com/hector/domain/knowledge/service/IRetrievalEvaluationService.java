package com.hector.domain.knowledge.service;

import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationReadinessVO;
import com.hector.domain.knowledge.model.valobj.RetrievalEvaluationResultVO;

import java.util.List;

/**
 * 供受控离线评测调用的当前检索链路适配服务。
 * 评测调用方不提供用户身份，服务仅使用 Workspace 权威 owner 建立 RetrievalContext。
 */
public interface IRetrievalEvaluationService {

    RetrievalEvaluationResultVO retrieve(Long workspaceId, String query, Integer candidateLimit, Integer topK);

    List<RetrievalEvaluationReadinessVO> readiness(Long workspaceId, List<String> documentCodes);
}
