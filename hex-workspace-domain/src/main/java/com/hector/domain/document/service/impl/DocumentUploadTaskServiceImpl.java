package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentUploadOutboxEventEntity;
import com.hector.domain.document.model.entity.DocumentUploadTaskEntity;
import com.hector.domain.document.model.valobj.DocumentUploadOutboxStatusVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.document.repository.IDocumentUploadOutboxEventRepository;
import com.hector.domain.document.repository.IDocumentUploadTaskRepository;
import com.hector.domain.document.service.IDocumentUploadTaskService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @Resource
    private IDocumentUploadTaskRepository taskRepository;

    @Resource
    private IDocumentUploadOutboxEventRepository outboxRepository;

    @Resource
    private IDocumentRepository documentRepository;

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
        try {
            // 2. 临时对象复制到固定正式路径；重复写入同一路径是幂等操作。
            byte[] content = storageRepository.download(task.getStagingPath());
            storageRepository.upload(task.getTargetPath(), content, "application/octet-stream");
            // 3. 正式对象成功后，在一个 MySQL 事务中同时完成任务和文档状态切换。
            boolean completed = executeInTransaction(() -> complete(task, executionToken));
            if (completed) {
                try {
                    storageRepository.delete(task.getStagingPath());
                } catch (Exception exception) {
                    log.warn("文档上传任务-清理临时对象失败 taskCode:{}", taskCode, exception);
                }
            }
        } catch (Exception exception) {
            log.error("文档上传任务-执行失败 taskCode:{} error:{}", taskCode, exception.getMessage(), exception);
            executeInTransaction(() -> {
                fail(taskCode, executionToken, exception);
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
            LocalDateTime now = LocalDateTime.now();
            taskRepository.recoverExpiredRunning(task.getTaskCode(), now, now,
                    "文档上传任务执行租约超时");
        }
    }

    private boolean complete(DocumentUploadTaskEntity task, String executionToken) {
        if (!taskRepository.completeIfOwned(task.getTaskCode(), executionToken)) {
            return false;
        }
        DocumentEntity document = documentRepository.findById(task.getDocumentId())
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        document.activate();
        documentRepository.update(document);
        return true;
    }

    private void fail(String taskCode, String executionToken, Exception exception) {
        taskRepository.markRetryIfOwned(taskCode, executionToken, exception.getMessage(),
                LocalDateTime.now().plusMinutes(RETRY_DELAY_MINUTES));
    }

    private <T> T executeInTransaction(java.util.function.Supplier<T> action) {
        T result = transactionTemplate.execute(status -> action.get());
        if (result == null) {
            throw new BusinessException(ResponseCode.SYSTEM_ERROR);
        }
        return result;
    }
}
