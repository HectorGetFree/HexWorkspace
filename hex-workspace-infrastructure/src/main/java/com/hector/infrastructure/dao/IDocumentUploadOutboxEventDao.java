package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.DocumentUploadOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 文档上传 Outbox 事件 DAO。 */
@Mapper
public interface IDocumentUploadOutboxEventDao {
    int insert(DocumentUploadOutboxEvent event);
    List<DocumentUploadOutboxEvent> queryPending(@Param("limit") int limit);
    int update(DocumentUploadOutboxEvent event);
}
