package com.hector.infrastructure.repository;

import com.hector.domain.document.model.entity.DocumentUploadTaskEntity;
import com.hector.domain.document.model.valobj.DocumentUploadTaskStatusVO;
import com.hector.domain.document.repository.IDocumentUploadTaskRepository;
import com.hector.infrastructure.dao.IDocumentUploadTaskDao;
import com.hector.infrastructure.dao.po.DocumentUploadTask;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 文档上传任务仓储实现。 */
@Repository
public class DocumentUploadTaskRepository implements IDocumentUploadTaskRepository {
    @Resource
    private IDocumentUploadTaskDao documentUploadTaskDao;

    @Override
    public DocumentUploadTaskEntity save(DocumentUploadTaskEntity task) {
        DocumentUploadTask documentUploadTask = toDocumentUploadTaskPo(task);
        documentUploadTaskDao.insert(documentUploadTask);
        return toDocumentUploadTaskEntity(documentUploadTask);
    }

    @Override
    public Optional<DocumentUploadTaskEntity> findByTaskCode(String taskCode) {
        DocumentUploadTask po = documentUploadTaskDao.queryByTaskCode(taskCode);
        return po == null ? Optional.empty() : Optional.of(toDocumentUploadTaskEntity(po));
    }

    @Override
    public boolean claimForRunning(String taskCode, String executionToken, LocalDateTime runningLeaseUntil) {
        return documentUploadTaskDao.claimForRunning(taskCode, executionToken, runningLeaseUntil) == 1;
    }

    @Override
    public boolean completeIfOwned(String taskCode, String executionToken) {
        return documentUploadTaskDao.completeIfOwned(taskCode, executionToken) == 1;
    }

    @Override
    public boolean markRetryIfOwned(String taskCode, String executionToken, String failureReason, LocalDateTime nextRetryAt) {
        return documentUploadTaskDao.markRetryIfOwned(taskCode, executionToken, failureReason, nextRetryAt) == 1;
    }

    @Override
    public List<DocumentUploadTaskEntity> listRetryDue(LocalDateTime now, int limit) {
        return documentUploadTaskDao.queryRetryDue(now, limit).stream().map(this::toDocumentUploadTaskEntity).toList();
    }

    @Override
    public boolean claimRetryForDispatch(String taskCode, LocalDateTime now) {
        return documentUploadTaskDao.claimRetryForDispatch(taskCode, now) == 1;
    }

    @Override
    public List<DocumentUploadTaskEntity> listExpiredRunning(LocalDateTime now, int limit) {
        return documentUploadTaskDao.queryExpiredRunning(now, limit).stream().map(this::toDocumentUploadTaskEntity).toList();
    }

    @Override
    public boolean recoverExpiredRunning(String taskCode, LocalDateTime now, LocalDateTime nextRetryAt, String failureReason) {
        return documentUploadTaskDao.recoverExpiredRunning(taskCode, now, nextRetryAt, failureReason) == 1;
    }

    private DocumentUploadTask toDocumentUploadTaskPo(DocumentUploadTaskEntity entity) {
        return DocumentUploadTask.builder()
                .id(entity.getId())
                .taskCode(entity.getTaskCode())
                .documentId(entity.getDocumentId())
                .stagingPath(entity.getStagingPath())
                .targetPath(entity.getTargetPath())
                .status(entity.getStatus().getCode())
                .executionToken(entity.getExecutionToken())
                .runningLeaseUntil(entity.getRunningLeaseUntil())
                .retryCount(entity.getRetryCount())
                .nextRetryAt(entity.getNextRetryAt())
                .failureReason(entity.getFailureReason())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private DocumentUploadTaskEntity toDocumentUploadTaskEntity(DocumentUploadTask po) {
        return DocumentUploadTaskEntity.builder()
                .id(po.getId())
                .taskCode(po.getTaskCode())
                .documentId(po.getDocumentId())
                .stagingPath(po.getStagingPath())
                .targetPath(po.getTargetPath())
                .status(DocumentUploadTaskStatusVO.fromCode(po.getStatus()))
                .executionToken(po.getExecutionToken())
                .runningLeaseUntil(po.getRunningLeaseUntil())
                .retryCount(po.getRetryCount())
                .nextRetryAt(po.getNextRetryAt())
                .failureReason(po.getFailureReason())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}
