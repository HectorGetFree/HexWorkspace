package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentUploadTaskEntity;
import com.hector.domain.document.model.valobj.DocumentStorageObjectVO;
import com.hector.domain.document.model.valobj.DocumentUploadTaskStatusVO;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.document.repository.IDocumentUploadTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentStagingCleanupServiceImplTest {

    @Mock
    private IDocumentStorageRepository storageRepository;

    @Mock
    private IDocumentUploadTaskRepository taskRepository;

    @InjectMocks
    private DocumentStagingCleanupServiceImpl stagingCleanupService;

    @Test
    void shouldDeleteOnlyExpiredObjectsWithoutUploadTaskReference() {
        LocalDateTime now = LocalDateTime.now();
        DocumentStorageObjectVO orphan = storageObject("staging/documents/orphan", now.minusHours(25));
        DocumentStorageObjectVO referenced = storageObject("staging/documents/referenced", now.minusHours(25));
        DocumentStorageObjectVO recent = storageObject("staging/documents/recent", now.minusHours(1));
        when(storageRepository.listByPrefix("staging/")).thenReturn(List.of(orphan, referenced, recent));
        when(taskRepository.existsByStagingPath(orphan.getObjectKey())).thenReturn(false);
        when(taskRepository.existsByStagingPath(referenced.getObjectKey())).thenReturn(true);

        int deletedCount = stagingCleanupService.cleanExpiredOrphans(now.minusHours(24));

        assertEquals(1, deletedCount);
        verify(storageRepository).delete(orphan.getObjectKey());
        verify(storageRepository, never()).delete(referenced.getObjectKey());
        verify(storageRepository, never()).delete(recent.getObjectKey());
    }

    @Test
    void shouldDeleteExpiredTerminalTaskStagingObjectAndClearPathReference() {
        LocalDateTime expiredBefore = LocalDateTime.now().minusHours(24);
        DocumentUploadTaskEntity succeededTask = DocumentUploadTaskEntity.builder()
                .taskCode("task_succeeded")
                .stagingPath("staging/documents/succeeded")
                .status(DocumentUploadTaskStatusVO.SUCCEEDED)
                .build();
        DocumentUploadTaskEntity failedTask = DocumentUploadTaskEntity.builder()
                .taskCode("task_failed")
                .stagingPath("staging/documents/failed")
                .status(DocumentUploadTaskStatusVO.FAILED)
                .build();
        when(taskRepository.listExpiredTerminalTasksWithStagingPath(expiredBefore, 100))
                .thenReturn(List.of(succeededTask, failedTask));
        when(taskRepository.clearStagingPathIfTerminal("task_succeeded", "staging/documents/succeeded"))
                .thenReturn(true);
        when(taskRepository.clearStagingPathIfTerminal("task_failed", "staging/documents/failed"))
                .thenReturn(true);

        int deletedCount = stagingCleanupService.cleanExpiredTerminalTaskObjects(expiredBefore);

        assertEquals(2, deletedCount);
        verify(storageRepository).delete("staging/documents/succeeded");
        verify(storageRepository).delete("staging/documents/failed");
        verify(taskRepository).clearStagingPathIfTerminal("task_succeeded", "staging/documents/succeeded");
        verify(taskRepository).clearStagingPathIfTerminal("task_failed", "staging/documents/failed");
    }

    @Test
    void shouldKeepTerminalTaskPathReferenceWhenStagingDeletionFails() {
        LocalDateTime expiredBefore = LocalDateTime.now().minusHours(24);
        DocumentUploadTaskEntity failedTask = DocumentUploadTaskEntity.builder()
                .taskCode("task_failed")
                .stagingPath("staging/documents/failed")
                .status(DocumentUploadTaskStatusVO.FAILED)
                .build();
        when(taskRepository.listExpiredTerminalTasksWithStagingPath(expiredBefore, 100))
                .thenReturn(List.of(failedTask));
        doThrow(new IllegalStateException("MinIO 暂时不可用"))
                .when(storageRepository).delete("staging/documents/failed");

        int deletedCount = stagingCleanupService.cleanExpiredTerminalTaskObjects(expiredBefore);

        assertEquals(0, deletedCount);
        verify(taskRepository, never()).clearStagingPathIfTerminal("task_failed", "staging/documents/failed");
    }

    private DocumentStorageObjectVO storageObject(String objectKey, LocalDateTime lastModifiedAt) {
        return DocumentStorageObjectVO.builder()
                .objectKey(objectKey)
                .lastModifiedAt(lastModifiedAt)
                .build();
    }
}
