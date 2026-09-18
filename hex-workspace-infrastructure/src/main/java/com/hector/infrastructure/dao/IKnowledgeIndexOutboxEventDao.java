package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.KnowledgeIndexOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 知识索引 Outbox 事件 DAO。 */
@Mapper
public interface IKnowledgeIndexOutboxEventDao {

    /** 写入可靠投递事件。 */
    int insert(KnowledgeIndexOutboxEvent event);

    /** 查询等待投递的事件。 */
    List<KnowledgeIndexOutboxEvent> queryPending(@Param("limit") int limit);

    /** 通过条件更新抢占 MQ 发布权。 */
    int claimForPublishing(@Param("eventCode") String eventCode, @Param("publishingToken") String publishingToken,
                           @Param("publishingLeaseUntil") LocalDateTime publishingLeaseUntil);

    /** 仅当前发布者可将事件标记为已投递。 */
    int markSentIfOwned(@Param("eventCode") String eventCode, @Param("publishingToken") String publishingToken);

    /** 仅当前发布者可释放发布权。 */
    int releaseIfOwned(@Param("eventCode") String eventCode, @Param("publishingToken") String publishingToken,
                       @Param("failureReason") String failureReason);

    /** 查询发布租约已过期的事件。 */
    List<KnowledgeIndexOutboxEvent> queryExpiredPublishing(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 回收过期发布权。 */
    int recoverExpiredPublishing(@Param("eventCode") String eventCode, @Param("now") LocalDateTime now,
                                 @Param("failureReason") String failureReason);

    /** 为需要重试的任务重新开放已投递的 Outbox 邮箱。 */
    int resetSentForRetry(@Param("jobCode") String jobCode);
}
