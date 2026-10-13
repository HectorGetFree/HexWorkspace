package com.hector.domain.knowledge.model.valobj;

/**
 * 离线评测向同一 Reranker 提供 Candidate 的文本形态。
 *
 * <p>仅用于受 service token 保护的 evaluation endpoint；候选 identity 与正文仍从当前
 * Active Generation 权威 Artifact 读取，调用方不能提供或篡改文本。</p>
 */
public enum RetrievalEvaluationRerankInputVO {

    /** R0：仅 Chunk 正文。 */
    RAW_CHUNK,

    /** R1：文档标题、章节路径与 Chunk 正文。 */
    STRUCTURAL_CONTEXT
}
