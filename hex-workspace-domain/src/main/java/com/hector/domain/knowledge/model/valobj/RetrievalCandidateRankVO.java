package com.hector.domain.knowledge.model.valobj;

/** 单个文档在某一检索阶段的最佳候选排名；仅供受控评测诊断。 */
public record RetrievalCandidateRankVO(Long documentId, Integer rank, Double score) {
}
