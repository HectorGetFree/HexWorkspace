package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentUploadTaskEntity;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentUploadTaskStatusVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.document.repository.IDocumentUploadOutboxEventRepository;
import com.hector.domain.document.repository.IDocumentUploadTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentUploadTaskServiceImplTest {

    @Mock
    private IDocumentUploadTaskRepository taskRepository;

    @Mock
    private IDocumentUploadOutboxEventRepository outboxRepository;

    @Mock
    private IDocumentRepository documentRepository;

    @Mock
    private IDocumentStorageRepository storageRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private DocumentUploadTaskServiceImpl uploadTaskService;

    @Test
    void shouldRecoverExpiredRunningTaskToRetryWait() {
        mockTransactionTemplate();
        DocumentUploadTaskEntity task = task("task_001");
        when(taskRepository.listExpiredRunning(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(task));

        uploadTaskService.recoverExpiredRunningTasks();

        verify(taskRepository).recoverExpiredRunning(
                eq("task_001"), any(LocalDateTime.class), any(LocalDateTime.class),
                eq("文档上传任务执行租约超时"));
    }

    @Test
    void shouldNotDeleteStagingObjectWhenExecutionTokenExpired() {
        mockTransactionTemplate();
        DocumentUploadTaskEntity task = task("task_001");
        when(taskRepository.claimForRunning(eq("task_001"), anyString(), any(LocalDateTime.class)))
                .thenReturn(true);
        when(taskRepository.findByTaskCode("task_001")).thenReturn(Optional.of(task));
        when(storageRepository.download("staging/task_001")).thenReturn(new byte[]{1});
        when(taskRepository.completeIfOwned(eq("task_001"), anyString())).thenReturn(false);

        uploadTaskService.execute("task_001");

        verify(storageRepository, never()).delete("staging/task_001");
    }

    @Test
    void shouldMarkTaskAndDocumentFailedWhenRetryCountExhausted() {
        mockTransactionTemplate();
        DocumentUploadTaskEntity task = task("task_001", 3);
        DocumentEntity document = DocumentEntity.builder()
                .id(10L)
                .status(DocumentStatusVO.CREATING)
                .build();
        when(taskRepository.claimForRunning(eq("task_001"), anyString(), any(LocalDateTime.class)))
                .thenReturn(true);
        when(taskRepository.findByTaskCode("task_001")).thenReturn(Optional.of(task));
        when(storageRepository.download("staging/task_001")).thenThrow(new IllegalStateException("MinIO 拒绝访问"));
        when(taskRepository.markFailedIfOwned(eq("task_001"), anyString(), eq("MinIO 拒绝访问")))
                .thenReturn(true);
        when(documentRepository.findById(10L)).thenReturn(Optional.of(document));

        uploadTaskService.execute("task_001");

        verify(documentRepository).update(document);
        assertEquals(DocumentStatusVO.FAILED, document.getStatus());
    }

    private DocumentUploadTaskEntity task(String taskCode) {
        return task(taskCode, 0);
    }

    private DocumentUploadTaskEntity task(String taskCode, int retryCount) {
        return DocumentUploadTaskEntity.builder()
                .taskCode(taskCode)
                .documentId(10L)
                .stagingPath("staging/" + taskCode)
                .targetPath("document/" + taskCode)
                .status(DocumentUploadTaskStatusVO.RUNNING)
                .retryCount(retryCount)
                .build();
    }

    @SuppressWarnings("unchecked")
    private void mockTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
    }
}
