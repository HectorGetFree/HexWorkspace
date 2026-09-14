package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.DocumentUploadOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 文档上传 Outbox 事件 DAO。 */
@Mapper
public interface IDocumentUploadOutboxEventDao {
    int insert(DocumentUploadOutboxEvent event);
    List<DocumentUploadOutboxEvent> queryPending(@Param("limit") int limit);
    int claimForPublishing(@Param("eventCode") String eventCode, @Param("publishingToken") String publishingToken,
                           @Param("publishingLeaseUntil") LocalDateTime publishingLeaseUntil);
    int markSentIfOwned(@Param("eventCode") String eventCode, @Param("publishingToken") String publishingToken);
    int releaseIfOwned(@Param("eventCode") String eventCode, @Param("publishingToken") String publishingToken,
                       @Param("failureReason") String failureReason);
    List<DocumentUploadOutboxEvent> queryExpiredPublishing(@Param("now") LocalDateTime now, @Param("limit") int limit);
    int recoverExpiredPublishing(@Param("eventCode") String eventCode, @Param("now") LocalDateTime now,
                                 @Param("failureReason") String failureReason);
}
