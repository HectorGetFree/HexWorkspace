package com.hector.domain.knowledge.adapter.port;

import com.hector.domain.knowledge.model.valobj.KnowledgeSearchHitVO;
import com.hector.domain.knowledge.model.valobj.KnowledgeVectorPointVO;

import java.util.List;

/**
 * 向量库端口。MySQL 与 MinIO 才是可重建的事实来源，向量库可随时换库重建。
 */
public interface IVectorStore {

    void replaceDocumentVersion(Long workspaceId, Long documentId, Integer versionNumber,
                                List<KnowledgeVectorPointVO> points);

    List<KnowledgeSearchHitVO> search(Long workspaceId, List<Float> queryVector, int topK);
}
