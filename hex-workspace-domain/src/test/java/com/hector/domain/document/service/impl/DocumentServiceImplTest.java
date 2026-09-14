package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.service.IFolderService;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
        mockTransactionTemplate();
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

    private DocumentEntity document(String fileName, DocumentTypeVO type) {
        return DocumentEntity.builder()
                .id(30L)
                .documentCode("doc_pdf")
                .workspaceId(10L)
                .parentId(20L)
                .fileName(fileName)
                .documentType(type)
                .status(DocumentStatusVO.ACTIVE)
                .build();
    }

    @SuppressWarnings("unchecked")
    private void mockTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
    }
}
