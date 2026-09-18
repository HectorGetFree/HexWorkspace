package com.hector.domain.knowledge.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexOutboxEventEntity;

import java.time.LocalDateTime;
import java.util.List;

/** 知识索引 Outbox 事件仓储。 */
public interface IKnowledgeIndexOutboxEventRepository {

    /** 与索引任务在同一数据库事务中登记可靠消息。 */
    KnowledgeIndexOutboxEventEntity save(KnowledgeIndexOutboxEventEntity event);

    /**
     * 查询等待 MQ 投递的事件。
     *
     * @param limit 最大返回数量
     * @return 等待可靠投递的事件
     */
    List<KnowledgeIndexOutboxEventEntity> listPending(int limit);

    /** 仅当事件仍待投递时原子抢占本次 MQ 发布权。 */
    boolean claimForPublishing(String eventCode, String publishingToken, LocalDateTime publishingLeaseUntil);

    /** 仅当发布令牌仍匹配时标记 MQ 已确认接收。 */
    boolean markSentIfOwned(String eventCode, String publishingToken);

    /** 仅当发布令牌仍匹配时释放发布权，供后续重新投递。 */
    boolean releaseIfOwned(String eventCode, String publishingToken, String failureReason);

    /**
     * 查询发布租约已过期的事件。
     *
     * @param now 当前时间
     * @param limit 最大返回数量
     * @return 需要回收发布权的事件
     */
    List<KnowledgeIndexOutboxEventEntity> listExpiredPublishing(LocalDateTime now, int limit);

    /** 原子回收过期发布权，重新等待 MQ 投递。 */
    boolean recoverExpiredPublishing(String eventCode, LocalDateTime now, String failureReason);

    /** 已确认投递的事件对应任务需要重试时，重新变为可投递邮箱。 */
    boolean resetSentForRetry(String jobCode);
}
