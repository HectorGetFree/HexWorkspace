package com.hector.infrastructure.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexOutboxEventEntity;
import com.hector.domain.knowledge.model.valobj.KnowledgeIndexOutboxStatusVO;
import com.hector.domain.knowledge.repository.IKnowledgeIndexOutboxEventRepository;
import com.hector.infrastructure.dao.IKnowledgeIndexOutboxEventDao;
import com.hector.infrastructure.dao.po.KnowledgeIndexOutboxEvent;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

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
