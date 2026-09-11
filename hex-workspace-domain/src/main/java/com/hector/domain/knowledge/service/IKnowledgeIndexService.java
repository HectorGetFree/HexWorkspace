package com.hector.domain.knowledge.service;

import com.hector.domain.knowledge.model.valobj.KnowledgeSearchHitVO;

import java.util.List;

/**
 * 文档知识索引领域服务。
 */
public interface IKnowledgeIndexService {

    /**
     * 对已获得的纯文本建立索引。
     * 当前不由文档创建、上传或编辑流程调用，等待后续知识索引任务接入。
     */
    void indexDocument(Long workspaceId, Long documentId, String documentCode, String content);

    List<KnowledgeSearchHitVO> search(Long workspaceId, String question);
}
