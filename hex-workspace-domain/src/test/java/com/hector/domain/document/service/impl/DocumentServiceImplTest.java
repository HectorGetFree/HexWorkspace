package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentUploadTaskEntity;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.document.repository.IDocumentUploadOutboxEventRepository;
import com.hector.domain.document.repository.IDocumentUploadTaskRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.document.service.IDocumentUploadTaskService;
import com.hector.domain.knowledge.service.IKnowledgeIndexJobService;
import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.service.IFolderService;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock
    private IDocumentRepository documentRepository;

    @Mock
    private IFolderService folderService;

    @Mock
    private IDocumentStorageRepository documentStorageRepository;

    @Mock
    private IDocumentUploadTaskRepository documentUploadTaskRepository;

    @Mock
    private IDocumentUploadOutboxEventRepository documentUploadOutboxEventRepository;

    @Mock
    private IDocumentVersionRepository documentVersionRepository;

    @Mock
    private IDocumentUploadTaskService documentUploadTaskService;

    @Mock
    private IKnowledgeIndexJobService knowledgeIndexJobService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private DocumentServiceImpl documentService;

    @Test
    void shouldRejectExactDuplicateFileNameInSameFolder() {
        mockTransactionTemplate();
        when(folderService.getActiveFolder(10L, 20L)).thenReturn(FolderEntity.builder()
                .id(20L)
                .workspaceId(10L)
                .build());
        when(documentRepository.findActiveByParentIdAndFileName(10L, 20L, "方案.md"))
                .thenReturn(Optional.of(document("方案.md", DocumentTypeVO.PLATFORM_CREATED)));

        assertThrows(BusinessException.class,
                () -> documentService.createMarkdownDocument(10L, 1L, 20L, "方案", "内容"));
    }

    @Test
    void shouldRejectEditingNonMarkdownDocument() {
        when(documentRepository.findByDocumentCode("doc_pdf"))
                .thenReturn(Optional.of(document("方案.pdf", DocumentTypeVO.LOCAL_UPLOADED)));

        assertThrows(BusinessException.class,
                () -> documentService.editMarkdownDocument(10L, "doc_pdf", 1L, "内容"));
    }

    @Test
    void shouldExposeFailedDocumentInWorkspaceList() {
        when(documentRepository.listByWorkspaceId(10L)).thenReturn(List.of(
                document("已完成.md", DocumentTypeVO.PLATFORM_CREATED),
                DocumentEntity.builder()
                        .id(31L)
                        .documentCode("doc_failed")
                        .workspaceId(10L)
                        .fileName("失败.pdf")
                        .documentType(DocumentTypeVO.LOCAL_UPLOADED)
                        .status(DocumentStatusVO.FAILED)
                        .build(),
                DocumentEntity.builder()
                        .id(32L)
                        .documentCode("doc_creating")
                        .workspaceId(10L)
                        .fileName("上传中.pdf")
                        .documentType(DocumentTypeVO.LOCAL_UPLOADED)
                        .status(DocumentStatusVO.CREATING)
                        .build()));

        assertEquals(2, documentService.listVisibleDocuments(10L).size());
    }

    @Test
    void shouldRegisterVersionTwoTaskWhenSavingMarkdownContent() {
        mockTransactionTemplate();
        DocumentEntity document = document("方案.md", DocumentTypeVO.PLATFORM_CREATED);
        when(documentRepository.findByDocumentCode("doc_pdf")).thenReturn(Optional.of(document));
        when(documentRepository.startWritingIfCurrent(any(DocumentEntity.class), eq(1L), eq(0L))).thenReturn(true);
        when(documentStorageRepository.getBucketName()).thenReturn("hex-workspace");
        doAnswer(invocation -> {
            document.activateVersion(2L);
            return null;
        }).when(documentUploadTaskService).executeOwned(anyString(), anyString());

        DocumentEntity saved = documentService.editMarkdownDocument(10L, "doc_pdf", 1L, "v2 内容");

        ArgumentCaptor<DocumentUploadTaskEntity> taskCaptor = ArgumentCaptor.forClass(DocumentUploadTaskEntity.class);
        verify(documentUploadTaskRepository).save(taskCaptor.capture());
        assertEquals(2L, taskCaptor.getValue().getVersionNo());
        assertEquals("workspace/10/documents/doc_pdf/versions/2", taskCaptor.getValue().getTargetPath());
        assertEquals(2L, saved.getCurrentVersionNo());
    }

    @Test
    void shouldLogicallyDeleteDocumentWithoutChangingCurrentVersionOrContent() {
        mockTransactionTemplate();
        DocumentEntity document = document("方案.md", DocumentTypeVO.PLATFORM_CREATED);
        when(documentRepository.findByDocumentCode("doc_pdf")).thenReturn(Optional.of(document));

        DocumentEntity deleted = documentService.deleteDocument(10L, "doc_pdf");

        assertEquals(DocumentStatusVO.DELETED, deleted.getStatus());
        assertNotNull(deleted.getDeletedAt());
        assertEquals(1L, deleted.getCurrentVersionNo());
        verify(documentRepository).update(document);
        verifyNoInteractions(documentVersionRepository, documentStorageRepository);
    }

    @Test
    void shouldKeepFirstDeletedAtWhenDeletingDocumentRepeatedly() {
        mockTransactionTemplate();
        DocumentEntity document = document("方案.md", DocumentTypeVO.PLATFORM_CREATED);
        when(documentRepository.findByDocumentCode("doc_pdf")).thenReturn(Optional.of(document));

        documentService.deleteDocument(10L, "doc_pdf");
        LocalDateTime firstDeletedAt = document.getDeletedAt();
        documentService.deleteDocument(10L, "doc_pdf");

        assertEquals(firstDeletedAt, document.getDeletedAt());
        verify(documentRepository, times(1)).update(document);
    }

    @Test
    void shouldRestoreDeletedDocumentWithoutCreatingNewVersion() {
        mockTransactionTemplate();
        DocumentEntity document = document("方案.md", DocumentTypeVO.PLATFORM_CREATED);
        document.delete();
        when(documentRepository.findByDocumentCode("doc_pdf")).thenReturn(Optional.of(document));

        DocumentEntity restored = documentService.restoreDocument(10L, "doc_pdf");

        assertEquals(DocumentStatusVO.ACTIVE, restored.getStatus());
        assertNull(restored.getDeletedAt());
        assertEquals(1L, restored.getCurrentVersionNo());
        verify(documentRepository).update(document);
        verifyNoInteractions(documentVersionRepository, documentStorageRepository);
    }

    @Test
    void shouldRejectNormalAccessToDeletedDocument() {
        DocumentEntity document = document("方案.md", DocumentTypeVO.PLATFORM_CREATED);
        document.delete();
        when(documentRepository.findByDocumentCode("doc_pdf")).thenReturn(Optional.of(document));

        assertThrows(BusinessException.class, () -> documentService.getActiveDocument(10L, "doc_pdf"));
    }

    @Test
    void shouldRejectDocumentAccessWhenParentFolderIsInaccessible() {
        DocumentEntity document = document("方案.md", DocumentTypeVO.PLATFORM_CREATED);
        when(documentRepository.findByDocumentCode("doc_pdf")).thenReturn(Optional.of(document));
        doThrow(new BusinessException("R404", "文件夹不存在"))
                .when(folderService).getActiveFolder(10L, 20L);

        assertThrows(BusinessException.class, () -> documentService.getActiveDocument(10L, "doc_pdf"));
    }

    private DocumentEntity document(String fileName, DocumentTypeVO type) {
        return DocumentEntity.builder()
                .id(30L)
                .documentCode("doc_pdf")
                .workspaceId(10L)
                .parentId(20L)
                .fileName(fileName)
                .documentType(type)
                .status(DocumentStatusVO.ACTIVE)
                .currentVersionNo(1L)
                .build();
    }

    @SuppressWarnings("unchecked")
    private void mockTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
    }
}
