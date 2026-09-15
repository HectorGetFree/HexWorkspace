package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentUploadOutboxEventEntity;
import com.hector.domain.document.model.entity.DocumentUploadTaskEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.model.valobj.DocumentUploadOutboxStatusVO;
import com.hector.domain.document.model.valobj.DocumentStorageTypeVO;
import com.hector.domain.document.model.valobj.DocumentUploadTaskStatusVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.document.repository.IDocumentUploadOutboxEventRepository;
import com.hector.domain.document.repository.IDocumentUploadTaskRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.document.service.IDocumentUploadTaskService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

/** 文档上传任务领域服务，负责对象转正和失败重试登记。 */
@Service
public class DocumentUploadTaskServiceImpl implements IDocumentUploadTaskService {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadTaskServiceImpl.class);
    private static final int RETRY_DELAY_MINUTES = 1;
    private static final int BATCH_SIZE = 100;
    private static final int RUNNING_LEASE_MINUTES = 10;

    @Value("${hexworkspace.document-upload.max-retry-count:3}")
    private int maxRetryCount = 3;

    @Resource
    private IDocumentUploadTaskRepository taskRepository;

    @Resource
    private IDocumentUploadOutboxEventRepository outboxRepository;

    @Resource
    private IDocumentRepository documentRepository;

    @Resource
    private IDocumentVersionRepository documentVersionRepository;

    @Resource
    private IDocumentStorageRepository storageRepository;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Override
    public void execute(String taskCode) {
        // 1. 使用条件更新抢占任务，MQ 至少一次投递不会导致同一任务并发执行。
        String executionToken = UUID.randomUUID().toString();
        LocalDateTime runningLeaseUntil = LocalDateTime.now().plusMinutes(RUNNING_LEASE_MINUTES);
        boolean claimed = executeInTransaction(() -> taskRepository.claimForRunning(
                taskCode, executionToken, runningLeaseUntil));
        if (!claimed) {
            return;
        }
        DocumentUploadTaskEntity task = taskRepository.findByTaskCode(taskCode)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        executeClaimedTask(task, executionToken);
    }

    @Override
    public void executeOwned(String taskCode, String executionToken) {
        DocumentUploadTaskEntity task = taskRepository.findByTaskCode(taskCode)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        if (DocumentUploadTaskStatusVO.RUNNING != task.getStatus()
                || !executionToken.equals(task.getExecutionToken())) {
            return;
        }
        executeClaimedTask(task, executionToken);
    }

    private void executeClaimedTask(DocumentUploadTaskEntity task, String executionToken) {
        try {
            // 2. 由 MinIO 服务端复制临时对象到固定正式路径，避免文件内容经过消费者 JVM。
            storageRepository.copy(task.getStagingPath(), task.getTargetPath(), task.getContentType());
            // 3. 正式对象成功后，在一个 MySQL 事务中写入不可变版本、完成任务并切换当前版本指针。
            boolean completed = executeInTransaction(() -> complete(task, executionToken));
            if (completed) {
                try {
                    storageRepository.delete(task.getStagingPath());
                } catch (Exception exception) {
                    log.warn("文档上传任务-清理临时对象失败 taskCode:{}", task.getTaskCode(), exception);
                }
            }
        } catch (Exception exception) {
            log.error("文档上传任务-执行失败 taskCode:{} error:{}", task.getTaskCode(), exception.getMessage(), exception);
            executeInTransaction(() -> {
                fail(task, executionToken, exception);
                return true;
            });
        }
    }

    @Override
    public void enqueueRetryTasks() {
        for (DocumentUploadTaskEntity task : taskRepository.listRetryDue(LocalDateTime.now(), BATCH_SIZE)) {
            executeInTransaction(() -> {
                // 1. 多实例并发扫描时，只有条件更新成功的实例可重新登记 Outbox 事件。
                LocalDateTime now = LocalDateTime.now();
                if (!taskRepository.claimRetryForDispatch(task.getTaskCode(), now)) {
                    return true;
                }

                // 2. 状态切换与新事件登记处于同一事务，任一步失败都会回滚为 RETRY_WAIT。
                outboxRepository.save(DocumentUploadOutboxEventEntity.builder()
                        .eventCode("outbox_" + UUID.randomUUID().toString().replace("-", ""))
                        .taskCode(task.getTaskCode())
                        .status(DocumentUploadOutboxStatusVO.PENDING)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
                return true;
            });
        }
    }

    @Override
    public void recoverExpiredRunningTasks() {
        for (DocumentUploadTaskEntity task : taskRepository.listExpiredRunning(LocalDateTime.now(), BATCH_SIZE)) {
            executeInTransaction(() -> {
                LocalDateTime now = LocalDateTime.now();
                String failureReason = "文档上传任务执行租约超时";
                if (hasRetriesRemaining(task)) {
                    taskRepository.recoverExpiredRunning(task.getTaskCode(), now, now, failureReason);
                    return true;
                }

                markDocumentFailedWhenExpired(task, now, failureReason);
                return true;
            });
        }
    }

    private boolean complete(DocumentUploadTaskEntity task, String executionToken) {
        if (!taskRepository.completeIfOwned(task.getTaskCode(), executionToken)) {
            return false;
        }
        DocumentEntity document = documentRepository.findById(task.getDocumentId())
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        documentVersionRepository.save(DocumentVersionEntity.builder()
                .documentId(document.getId())
                .versionNo(task.getVersionNo())
                .storageType(DocumentStorageTypeVO.MINIO)
                .bucketName(task.getBucketName())
                .storagePath(task.getTargetPath())
                .contentType(task.getContentType())
                .contentSize(task.getContentSize())
                .contentHash(task.getContentHash())
                .createdBy(task.getCreatedBy())
                .createdAt(LocalDateTime.now())
                .build());
        document.activateVersion(task.getVersionNo());
        documentRepository.update(document);
        return true;
    }

    private void fail(DocumentUploadTaskEntity task, String executionToken, Exception exception) {
        if (hasRetriesRemaining(task)) {
            taskRepository.markRetryIfOwned(task.getTaskCode(), executionToken, exception.getMessage(),
                    LocalDateTime.now().plusMinutes(RETRY_DELAY_MINUTES));
            return;
        }

        markDocumentFailedIfOwned(task, executionToken, exception.getMessage());
    }

    private void markDocumentFailedIfOwned(DocumentUploadTaskEntity task, String executionToken, String failureReason) {
        if (!taskRepository.markFailedIfOwned(task.getTaskCode(), executionToken, failureReason)) {
            return;
        }
        markDocumentFailed(task, failureReason);
    }

    private void markDocumentFailedWhenExpired(DocumentUploadTaskEntity task, LocalDateTime now, String failureReason) {
        if (!taskRepository.markExpiredRunningFailed(task.getTaskCode(), now, failureReason)) {
            return;
        }
        markDocumentFailed(task, failureReason);
    }

    private void markDocumentFailed(DocumentUploadTaskEntity task, String failureReason) {
        DocumentEntity document = documentRepository.findById(task.getDocumentId())
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        document.failUpload();
        documentRepository.update(document);
        log.error("文档上传任务-重试次数耗尽 taskCode:{} documentId:{} failureReason:{}",
                task.getTaskCode(), task.getDocumentId(), failureReason);
    }

    private boolean hasRetriesRemaining(DocumentUploadTaskEntity task) {
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        return retryCount < maxRetryCount;
    }

    private <T> T executeInTransaction(java.util.function.Supplier<T> action) {
        T result = transactionTemplate.execute(status -> action.get());
        if (result == null) {
            throw new BusinessException(ResponseCode.SYSTEM_ERROR);
        }
        return result;
    }
}
