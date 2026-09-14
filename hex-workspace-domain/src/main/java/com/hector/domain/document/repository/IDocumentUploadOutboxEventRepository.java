package com.hector.domain.document.repository;

import com.hector.domain.document.model.entity.DocumentUploadOutboxEventEntity;

import java.time.LocalDateTime;
import java.util.List;

/** 文档上传 Outbox 事件仓储。 */
public interface IDocumentUploadOutboxEventRepository {

    /** 与文档和任务在同一 MySQL 事务中登记事件。 */
    DocumentUploadOutboxEventEntity save(DocumentUploadOutboxEventEntity event);

    /** 查询等待 MQ 投递的事件。 */
    List<DocumentUploadOutboxEventEntity> listPending(int limit);

    /** 仅当事件仍待投递时原子抢占本次 MQ 发布权。 */
    boolean claimForPublishing(String eventCode, String publishingToken, LocalDateTime publishingLeaseUntil);

    /** 仅当发布令牌仍匹配时标记 MQ 已确认接收。 */
    boolean markSentIfOwned(String eventCode, String publishingToken);

    /** 仅当发布令牌仍匹配时释放发布权，供后续重新投递。 */
    boolean releaseIfOwned(String eventCode, String publishingToken, String failureReason);

    /** 查询发布租约已过期的事件。 */
    List<DocumentUploadOutboxEventEntity> listExpiredPublishing(LocalDateTime now, int limit);

    /** 原子回收过期发布权，重新等待 MQ 投递。 */
    boolean recoverExpiredPublishing(String eventCode, LocalDateTime now, String failureReason);
}
