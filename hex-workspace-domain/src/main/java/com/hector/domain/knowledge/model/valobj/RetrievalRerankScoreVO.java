package com.hector.domain.knowledge.model.valobj;

/** Reranker 对单次调用内候选的相关性评分。 */
public record RetrievalRerankScoreVO(Integer index, Double relevanceScore) {
}
