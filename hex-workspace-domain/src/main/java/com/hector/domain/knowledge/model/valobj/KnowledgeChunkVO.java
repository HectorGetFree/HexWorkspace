package com.hector.domain.knowledge.model.valobj;

import java.util.List;

/** 绑定 Generation 的结构感知 Chunk。 */
public record KnowledgeChunkVO(
        String generationId,
        Long documentId,
        Long versionNo,
        int chunkNo,
        String text,
        List<String> headingPath,
        int sourceBlockStart,
        int sourceBlockEnd,
        Integer pageStart,
        Integer pageEnd,
        String contentHash) {
}
