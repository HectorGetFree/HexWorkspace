package com.hector.domain.knowledge.model.valobj;

import java.util.List;

/** Qdrant 返回的非权威候选；所有身份字段都必须经 MySQL 复核。 */
public record RetrievalCandidateVO(
        String pointId,
        Long workspaceId,
        Long documentId,
        Long versionNo,
        Long indexInputRevision,
        String generationId,
        Integer chunkNo,
        List<String> headingPath,
        String content,
        Integer pageStart,
        Integer pageEnd,
        Double score) {
}
