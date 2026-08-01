package com.hector.domain.knowledge.model.valobj;

/**
 * 可被检索的文档片段。
 * heading 保留章节上下文，避免向量检索只命中脱离语义边界的一小段正文。
 */
public record KnowledgeSegmentVO(Integer chunkNo, String heading, String content, String contentHash) {
}
