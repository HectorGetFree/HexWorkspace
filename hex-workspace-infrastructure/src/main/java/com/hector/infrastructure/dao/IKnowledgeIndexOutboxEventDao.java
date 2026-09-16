package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.KnowledgeIndexOutboxEvent;
import org.apache.ibatis.annotations.Mapper;

/** 知识索引 Outbox 事件 DAO。 */
@Mapper
public interface IKnowledgeIndexOutboxEventDao {

    int insert(KnowledgeIndexOutboxEvent event);
}
