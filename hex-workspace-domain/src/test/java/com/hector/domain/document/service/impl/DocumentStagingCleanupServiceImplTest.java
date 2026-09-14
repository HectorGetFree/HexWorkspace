package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.valobj.DocumentStorageObjectVO;
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

    private DocumentStorageObjectVO storageObject(String objectKey, LocalDateTime lastModifiedAt) {
        return DocumentStorageObjectVO.builder()
                .objectKey(objectKey)
                .lastModifiedAt(lastModifiedAt)
                .build();
    }
}
