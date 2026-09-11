package com.hector.infrastructure.repository;

import com.hector.domain.document.model.entity.DocumentUploadOutboxEventEntity;
import com.hector.domain.document.model.valobj.DocumentUploadOutboxStatusVO;
import com.hector.domain.document.repository.IDocumentUploadOutboxEventRepository;
import com.hector.infrastructure.dao.IDocumentUploadOutboxEventDao;
import com.hector.infrastructure.dao.po.DocumentUploadOutboxEvent;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

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
    public void update(DocumentUploadOutboxEventEntity event) {
        dao.update(toDocumentUploadOutboxEventPo(event));
    }

    private DocumentUploadOutboxEvent toDocumentUploadOutboxEventPo(DocumentUploadOutboxEventEntity entity) {
        return DocumentUploadOutboxEvent.builder()
                .id(entity.getId())
                .eventCode(entity.getEventCode())
                .taskCode(entity.getTaskCode())
                .status(entity.getStatus().getCode())
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
                .failureReason(po.getFailureReason())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}
