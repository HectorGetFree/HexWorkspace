package com.hector.domain.knowledge.adapter.port;

import com.hector.domain.knowledge.model.valobj.KnowledgeChunkVO;
import com.hector.domain.knowledge.model.valobj.ParsedDocumentVO;

import java.util.List;

/** 将结构化解析结果切分为稳定、可追溯的 Chunk。 */
public interface IParsedDocumentChunker {

    List<KnowledgeChunkVO> chunk(String generationId, Long documentId, Long versionNo, ParsedDocumentVO parsedDocument);
}
