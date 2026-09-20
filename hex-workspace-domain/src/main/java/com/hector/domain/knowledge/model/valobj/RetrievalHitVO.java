package com.hector.domain.knowledge.model.valobj;

import java.util.List;

/** 单条可信检索命中，标题来自 MySQL 当前 Document。 */
public record RetrievalHitVO(
        Long documentId,
        String documentCode,
        Long versionNo,
        String generationId,
        Integer chunkNo,
        String title,
        List<String> headingPath,
        String content,
        Integer pageStart,
        Integer pageEnd,
        Double score) {
}
