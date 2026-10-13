package com.hector.domain.knowledge.model.valobj;

/** 当前 Active Generation 中单个 Chunk 的评测候选排名；仅供受控评测诊断。 */
public record RetrievalCandidateRankVO(
        Long documentId,
        String generationId,
        Integer chunkNo,
        Integer rank,
        Double score) {

    /** 兼容既有仅按文档归因的评测测试与调用点。 */
    public RetrievalCandidateRankVO(Long documentId, Integer rank, Double score) {
        this(documentId, null, null, rank, score);
    }
}
