package com.hector.infrastructure.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexOutboxEventEntity;
import com.hector.domain.knowledge.model.valobj.KnowledgeIndexOutboxStatusVO;
import com.hector.domain.knowledge.repository.IKnowledgeIndexOutboxEventRepository;
import com.hector.infrastructure.dao.IKnowledgeIndexOutboxEventDao;
import com.hector.infrastructure.dao.po.KnowledgeIndexOutboxEvent;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** 知识索引 Outbox 事件仓储实现。 */
@Repository
public class KnowledgeIndexOutboxEventRepository implements IKnowledgeIndexOutboxEventRepository {

    @Resource
    private IKnowledgeIndexOutboxEventDao dao;

    @Override
    public KnowledgeIndexOutboxEventEntity save(KnowledgeIndexOutboxEventEntity event) {
        KnowledgeIndexOutboxEvent outboxEvent = toKnowledgeIndexOutboxEventPo(event);
        dao.insert(outboxEvent);
        return toKnowledgeIndexOutboxEventEntity(outboxEvent);
    }

    @Override
    public List<KnowledgeIndexOutboxEventEntity> listPending(int limit) {
        return dao.queryPending(limit).stream().map(this::toKnowledgeIndexOutboxEventEntity).toList();
    }

    @Override
    public boolean claimForPublishing(String eventCode, String publishingToken, LocalDateTime publishingLeaseUntil) {
        return dao.claimForPublishing(eventCode, publishingToken, publishingLeaseUntil) == 1;
    }

    @Override
    public boolean markSentIfOwned(String eventCode, String publishingToken) {
        return dao.markSentIfOwned(eventCode, publishingToken) == 1;
    }

    @Override
    public boolean releaseIfOwned(String eventCode, String publishingToken, String failureReason) {
        return dao.releaseIfOwned(eventCode, publishingToken, failureReason) == 1;
    }

    @Override
    public List<KnowledgeIndexOutboxEventEntity> listExpiredPublishing(LocalDateTime now, int limit) {
        return dao.queryExpiredPublishing(now, limit).stream().map(this::toKnowledgeIndexOutboxEventEntity).toList();
    }

    @Override
    public boolean recoverExpiredPublishing(String eventCode, LocalDateTime now, String failureReason) {
        return dao.recoverExpiredPublishing(eventCode, now, failureReason) == 1;
    }

    @Override
    public boolean resetSentForRetry(String jobCode) {
        return dao.resetSentForRetry(jobCode) == 1;
    }

    private KnowledgeIndexOutboxEvent toKnowledgeIndexOutboxEventPo(KnowledgeIndexOutboxEventEntity entity) {
        return KnowledgeIndexOutboxEvent.builder()
                .id(entity.getId())
                .eventCode(entity.getEventCode())
                .jobCode(entity.getJobCode())
                .status(entity.getStatus().getCode())
                .publishingToken(entity.getPublishingToken())
                .publishingLeaseUntil(entity.getPublishingLeaseUntil())
                .failureReason(entity.getFailureReason())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private KnowledgeIndexOutboxEventEntity toKnowledgeIndexOutboxEventEntity(KnowledgeIndexOutboxEvent outboxEvent) {
        return KnowledgeIndexOutboxEventEntity.builder()
                .id(outboxEvent.getId())
                .eventCode(outboxEvent.getEventCode())
                .jobCode(outboxEvent.getJobCode())
                .status(KnowledgeIndexOutboxStatusVO.fromCode(outboxEvent.getStatus()))
                .publishingToken(outboxEvent.getPublishingToken())
                .publishingLeaseUntil(outboxEvent.getPublishingLeaseUntil())
                .failureReason(outboxEvent.getFailureReason())
                .createdAt(outboxEvent.getCreatedAt())
                .updatedAt(outboxEvent.getUpdatedAt())
                .build();
    }
}
