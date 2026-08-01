package com.hector.domain.knowledge.adapter.port;

import com.hector.domain.knowledge.model.valobj.KnowledgeSearchHitVO;

import java.util.List;

/**
 * 向量库端口。MySQL 与 MinIO 才是可重建的事实来源，向量库可随时换库重建。
 */
public interface IVectorStore {

    void replaceDocumentVersion(Long tenantId, Long documentId, Integer versionNumber,
                                List<String> pointIds, List<List<Float>> vectors,
                                List<String> headings, List<String> contents);

    List<KnowledgeSearchHitVO> search(Long tenantId, List<Float> queryVector, int topK);
}
