package com.hector.domain.knowledge.model.valobj;

/** 单份评测语料是否已具备当前 Generation 检索条件。 */
public record RetrievalEvaluationReadinessVO(
        String documentCode,
        Long documentId,
        Long versionId,
        boolean ready,
        String reason) {
}
