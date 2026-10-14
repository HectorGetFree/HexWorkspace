package com.hector.domain.knowledge.model.valobj;

import java.util.List;

/** 评测专用的当前 Active Generation Chunk 快照，不属于用户检索协议。 */
public record RetrievalEvaluationChunkVO(
        String documentCode,
        String generationId,
        Integer chunkNo,
        String content,
        List<String> headingPath,
        String contentHash) {
}
