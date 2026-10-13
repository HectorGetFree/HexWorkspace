package com.hector.domain.knowledge.model.valobj;

/** 评测接口可公开的候选排名，不暴露数据库主键或额外正文。 */
public record RetrievalEvaluationCandidateVO(
        String documentCode,
        String generationId,
        Integer chunkNo,
        Integer rank,
        Double score) {

    /** 兼容既有 document-level diagnostics 消费方。 */
    public RetrievalEvaluationCandidateVO(String documentCode, Integer rank, Double score) {
        this(documentCode, null, null, rank, score);
    }
}
