package com.hector.infrastructure.repository;

import com.hector.domain.document.model.entity.DocumentUploadOutboxEventEntity;
import com.hector.domain.document.model.valobj.DocumentUploadOutboxStatusVO;
import com.hector.domain.document.repository.IDocumentUploadOutboxEventRepository;
import com.hector.infrastructure.dao.IDocumentUploadOutboxEventDao;
import com.hector.infrastructure.dao.po.DocumentUploadOutboxEvent;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** 文档上传 Outbox 事件仓储实现。 */
@Repository
public class DocumentUploadOutboxEventRepository implements IDocumentUploadOutboxEventRepository {
    @Resource
    private IDocumentUploadOutboxEventDao dao;

    @Override
    public DocumentUploadOutboxEventEntity save(DocumentUploadOutboxEventEntity event) {
        DocumentUploadOutboxEvent documentUploadOutboxEvent = toDocumentUploadOutboxEventPo(event);
        dao.insert(documentUploadOutboxEvent);
        return toDocumentUploadOutboxEventEntity(documentUploadOutboxEvent);
    }

    @Override
    public List<DocumentUploadOutboxEventEntity> listPending(int limit) {
        return dao.queryPending(limit).stream().map(this::toDocumentUploadOutboxEventEntity).toList();
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
    public List<DocumentUploadOutboxEventEntity> listExpiredPublishing(LocalDateTime now, int limit) {
        return dao.queryExpiredPublishing(now, limit).stream().map(this::toDocumentUploadOutboxEventEntity).toList();
    }

    @Override
    public boolean recoverExpiredPublishing(String eventCode, LocalDateTime now, String failureReason) {
        return dao.recoverExpiredPublishing(eventCode, now, failureReason) == 1;
    }

    private DocumentUploadOutboxEvent toDocumentUploadOutboxEventPo(DocumentUploadOutboxEventEntity entity) {
        return DocumentUploadOutboxEvent.builder()
                .id(entity.getId())
                .eventCode(entity.getEventCode())
                .taskCode(entity.getTaskCode())
                .status(entity.getStatus().getCode())
                .publishingToken(entity.getPublishingToken())
                .publishingLeaseUntil(entity.getPublishingLeaseUntil())
                .failureReason(entity.getFailureReason())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private DocumentUploadOutboxEventEntity toDocumentUploadOutboxEventEntity(DocumentUploadOutboxEvent po) {
        return DocumentUploadOutboxEventEntity.builder()
                .id(po.getId())
                .eventCode(po.getEventCode())
                .taskCode(po.getTaskCode())
                .status(DocumentUploadOutboxStatusVO.fromCode(po.getStatus()))
                .publishingToken(po.getPublishingToken())
                .publishingLeaseUntil(po.getPublishingLeaseUntil())
                .failureReason(po.getFailureReason())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}
