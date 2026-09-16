package com.hector.domain.knowledge.service;

import com.hector.domain.document.model.entity.DocumentEntity;

/** 索引任务登记服务；仅登记可靠异步意图，不在事务内执行索引。 */
public interface IKnowledgeIndexJobService {
    void registerAutomatic(DocumentEntity document);
}
