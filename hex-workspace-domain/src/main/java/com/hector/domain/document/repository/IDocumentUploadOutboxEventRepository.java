package com.hector.domain.document.repository;

import com.hector.domain.document.model.entity.DocumentUploadOutboxEventEntity;

import java.util.List;

/** 文档上传 Outbox 事件仓储。 */
public interface IDocumentUploadOutboxEventRepository {

    /** 与文档和任务在同一 MySQL 事务中登记事件。 */
    DocumentUploadOutboxEventEntity save(DocumentUploadOutboxEventEntity event);

    /** 查询等待 MQ 投递的事件。 */
    List<DocumentUploadOutboxEventEntity> listPending(int limit);

    /** 更新事件投递状态。 */
    void update(DocumentUploadOutboxEventEntity event);
}
