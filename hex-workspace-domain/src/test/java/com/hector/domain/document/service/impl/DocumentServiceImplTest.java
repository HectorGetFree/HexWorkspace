package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.file.service.IFileService;
import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.service.IFolderService;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock
    private IDocumentRepository documentRepository;

    @Mock
    private IFolderService folderService;

    @Mock
    private IFileService fileService;

    @InjectMocks
    private DocumentServiceImpl documentService;

    @Test
    void shouldRejectExactDuplicateFileNameInSameFolder() {
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

    private DocumentEntity document(String fileName, DocumentTypeVO type) {
        return DocumentEntity.builder()
                .id(30L)
                .documentCode("doc_pdf")
                .workspaceId(10L)
                .parentId(20L)
                .fileName(fileName)
                .documentType(type)
                .status(DocumentStatusVO.ACTIVE)
                .currentVersion(1)
                .build();
    }
}
