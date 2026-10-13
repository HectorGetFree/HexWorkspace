package com.hector.domain.knowledge.model.valobj;

import java.util.List;

/** 离线评测的外部候选重排结果；不属于用户检索或 Capability 协议。 */
public record RetrievalEvaluationRerankResultVO(
        List<RetrievalEvaluationCandidateVO> candidates,
        boolean rerankApplied) {

    public RetrievalEvaluationRerankResultVO {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
