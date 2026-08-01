package com.hector.domain.knowledge.model.valobj;

/**
 * 向量检索命中结果。分数由向量库按余弦相似度返回。
 */
public record KnowledgeSearchHitVO(Long documentId, String documentCode, Integer versionNumber, Integer chunkNo,
                                   String heading, String content, Double score) {
}
