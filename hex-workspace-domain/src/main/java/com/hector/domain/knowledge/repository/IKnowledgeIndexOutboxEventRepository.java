package com.hector.domain.knowledge.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexOutboxEventEntity;

/** 知识索引 Outbox 事件仓储。 */
public interface IKnowledgeIndexOutboxEventRepository {

    /** 与索引任务在同一数据库事务中登记可靠消息。 */
    KnowledgeIndexOutboxEventEntity save(KnowledgeIndexOutboxEventEntity event);
}
