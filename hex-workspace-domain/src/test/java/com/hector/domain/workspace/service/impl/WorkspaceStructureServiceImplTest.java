package com.hector.domain.workspace.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.service.IDocumentService;
import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.model.valobj.FolderStatusVO;
import com.hector.domain.folder.service.IFolderService;
import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.model.valobj.WorkspaceStatusVO;
import com.hector.domain.workspace.model.valobj.WorkspaceStructureResultVO;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceStructureServiceImplTest {

    @Test
    void truncationUsesVisibleChildrenAfterStableOrdering() {
        IFolderService folders = mock(IFolderService.class);
        IDocumentService documents = mock(IDocumentService.class);
        IWorkspaceRepository workspaces = mock(IWorkspaceRepository.class);
        IDocumentVersionRepository versions = mock(IDocumentVersionRepository.class);
        when(workspaces.findById(9L)).thenReturn(Optional.of(WorkspaceEntity.builder()
                .id(9L).ownerUserId(7L).status(WorkspaceStatusVO.ACTIVE).build()));
        when(folders.ensureRootFolder(9L)).thenReturn(folder(1L, "root", true));
        when(folders.listChildren(9L, 1L)).thenReturn(List.of(
                folder(10L, "z-folder", true), folder(11L, "hidden-folder", false)));
        when(documents.listVisibleDocumentsByParentId(9L, 1L)).thenReturn(List.of(
                document(20L, "a.md", 2L, true), document(21L, "deleted.md", 3L, false)));

        when(versions.findByDocumentIdAndVersionNo(20L, 2L)).thenReturn(Optional.of(
                DocumentVersionEntity.builder().id(900L).documentId(20L).versionNo(2L).build()));

        WorkspaceStructureResultVO result = new WorkspaceStructureServiceImpl(folders, documents, workspaces, versions)
                .listChildren(7L, 9L, null, 2);

        assertEquals(2, result.items().size());
        assertEquals("z-folder", result.items().get(0).name());
        assertEquals("a.md", result.items().get(1).name());
        // 目录返回版本主键 900，而不是文档内版本号 2。
        assertEquals(900L, result.items().get(1).versionId());
        assertFalse(result.truncated());
    }

    @Test
    void missingVersionFailsInsteadOfReturningVersionNumber() {
        IFolderService folders = mock(IFolderService.class);
        IDocumentService documents = mock(IDocumentService.class);
        IWorkspaceRepository workspaces = mock(IWorkspaceRepository.class);
        IDocumentVersionRepository versions = mock(IDocumentVersionRepository.class);
        when(workspaces.findById(9L)).thenReturn(Optional.of(WorkspaceEntity.builder()
                .id(9L).ownerUserId(7L).status(WorkspaceStatusVO.ACTIVE).build()));
        when(folders.ensureRootFolder(9L)).thenReturn(folder(1L, "root", true));
        when(folders.listChildren(9L, 1L)).thenReturn(List.of());
        when(documents.listVisibleDocumentsByParentId(9L, 1L))
                .thenReturn(List.of(document(20L, "a.md", 2L, true)));
        when(versions.findByDocumentIdAndVersionNo(20L, 2L)).thenReturn(Optional.empty());
        WorkspaceStructureServiceImpl service = new WorkspaceStructureServiceImpl(
                folders, documents, workspaces, versions);
        assertThrows(IllegalStateException.class, () -> service.listChildren(7L, 9L, null, 100));
    }

    @Test
    void exactLimitIsNotTruncatedButLimitPlusOneIs() {
        IFolderService folders = mock(IFolderService.class);
        IDocumentService documents = mock(IDocumentService.class);
        IWorkspaceRepository workspaces = mock(IWorkspaceRepository.class);
        IDocumentVersionRepository versions = mock(IDocumentVersionRepository.class);
        when(workspaces.findById(9L)).thenReturn(Optional.of(WorkspaceEntity.builder()
                .id(9L).ownerUserId(7L).status(WorkspaceStatusVO.ACTIVE).build()));
        when(folders.ensureRootFolder(9L)).thenReturn(folder(1L, "root", true));
        when(folders.listChildren(9L, 1L)).thenReturn(List.of(
                folder(10L, "a", true), folder(11L, "b", true), folder(12L, "c", true)));
        when(documents.listVisibleDocumentsByParentId(9L, 1L)).thenReturn(List.of());

        WorkspaceStructureServiceImpl service = new WorkspaceStructureServiceImpl(folders, documents, workspaces, versions);
        assertFalse(service.listChildren(7L, 9L, null, 3).truncated());
        WorkspaceStructureResultVO truncated = service.listChildren(7L, 9L, null, 2);
        assertEquals(2, truncated.items().size());
        assertTrue(truncated.truncated());
    }

    @Test
    void rejectsForeignWorkspaceBeforeResolvingFolder() {
        IFolderService folders = mock(IFolderService.class);
        IDocumentService documents = mock(IDocumentService.class);
        IWorkspaceRepository workspaces = mock(IWorkspaceRepository.class);
        IDocumentVersionRepository versions = mock(IDocumentVersionRepository.class);
        when(workspaces.findById(9L)).thenReturn(Optional.of(WorkspaceEntity.builder()
                .id(9L).ownerUserId(99L).status(WorkspaceStatusVO.ACTIVE).build()));

        WorkspaceStructureServiceImpl service = new WorkspaceStructureServiceImpl(folders, documents, workspaces, versions);

        assertThrows(IllegalStateException.class, () -> service.listChildren(7L, 9L, 1L, 100));
    }

    private FolderEntity folder(Long id, String name, boolean active) {
        return FolderEntity.builder().id(id).name(name).root(id == 1L)
                .status(active ? FolderStatusVO.ACTIVE : FolderStatusVO.DELETED).build();
    }

    private DocumentEntity document(Long id, String fileName, Long versionNo, boolean active) {
        return DocumentEntity.builder().id(id).fileName(fileName).currentVersionNo(versionNo)
                .status(active ? DocumentStatusVO.ACTIVE : DocumentStatusVO.DELETED).build();
    }
}
