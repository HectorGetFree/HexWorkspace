package com.hector.domain.knowledge.model.valobj;

/** Reranker 的无状态输入；index 只在单次调用内关联原始合法候选。 */
public record RetrievalRerankCandidateVO(Integer index, String text) {
}
