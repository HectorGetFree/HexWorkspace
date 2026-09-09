package com.hector.domain.knowledge.service;

import com.hector.domain.document.event.DocumentParseRequestedEvent;
import com.hector.domain.knowledge.model.valobj.KnowledgeSearchHitVO;

import java.util.List;

/**
 * 文档知识索引领域服务。
 */
public interface IKnowledgeIndexService {

    void indexParsedDocument(DocumentParseRequestedEvent event, Long extractedContentId);

    List<KnowledgeSearchHitVO> search(Long workspaceId, String question);
}
