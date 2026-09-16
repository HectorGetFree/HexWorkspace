package com.hector.domain.folder.service.impl;

import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.model.valobj.FolderStatusVO;
import com.hector.domain.folder.repository.IFolderRepository;
import com.hector.domain.folder.service.IFolderTreeLock;
import com.hector.domain.folder.service.IFolderTreeService;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FolderServiceImplTest {

    @Mock private IFolderRepository folderRepository;
    @Mock private IFolderTreeService folderTreeService;
    @Mock private IFolderTreeLock folderTreeLock;
    @Mock private TransactionTemplate transactionTemplate;
    @InjectMocks private FolderServiceImpl folderService;

    @Test
    void shouldRejectDuplicateFolderNameUnderSameParent() {
        mockTransactionTemplate();
        FolderEntity parent = folder(1L, null, true, "根目录", FolderStatusVO.ACTIVE);
        when(folderTreeService.requireAccessible(10L, 1L)).thenReturn(parent);
        when(folderRepository.findActiveByParentIdAndName(10L, 1L, "项目"))
                .thenReturn(Optional.of(folder(2L, 1L, false, "项目", FolderStatusVO.ACTIVE)));

        assertThrows(BusinessException.class, () -> folderService.createFolder(10L, 1L, "项目"));
    }

    @Test
    void shouldRejectMovingFolderIntoItsDescendant() {
        mockTransactionTemplate();
        FolderEntity source = folder(2L, 1L, false, "项目", FolderStatusVO.ACTIVE);
        FolderEntity child = folder(3L, 2L, false, "子目录", FolderStatusVO.ACTIVE);
        when(folderTreeService.requireAccessible(10L, 2L)).thenReturn(source);
        when(folderTreeService.requireAccessible(10L, 3L)).thenReturn(child);
        when(folderTreeService.hasAncestor(10L, 3L, 2L)).thenReturn(true);

        assertThrows(BusinessException.class, () -> folderService.moveFolder(10L, 2L, 3L));
    }

    @Test
    void shouldDeleteTargetOnlyAndKeepFirstDeletedAt() {
        mockTransactionTemplate();
        FolderEntity folder = folder(2L, 1L, false, "项目", FolderStatusVO.ACTIVE);
        when(folderRepository.findById(2L)).thenReturn(Optional.of(folder));
        when(folderTreeService.requireAccessible(10L, 2L)).thenReturn(folder);

        FolderEntity deleted = folderService.deleteFolder(10L, 2L);

        assertEquals(FolderStatusVO.DELETED, deleted.getStatus());
        assertNotNull(deleted.getDeletedAt());
        verify(folderRepository).update(deleted);
    }

    @Test
    void shouldKeepDeletedAtWhenDeletingFolderRepeatedly() {
        mockTransactionTemplate();
        LocalDateTime firstDeletedAt = LocalDateTime.now().minusDays(1);
        FolderEntity folder = FolderEntity.builder().id(2L).workspaceId(10L).parentId(1L).name("项目")
                .root(false).status(FolderStatusVO.DELETED).deletedAt(firstDeletedAt).build();
        when(folderRepository.findById(2L)).thenReturn(Optional.of(folder));

        FolderEntity deleted = folderService.deleteFolder(10L, 2L);

        assertEquals(firstDeletedAt, deleted.getDeletedAt());
        verify(folderRepository, never()).update(any());
        verify(folderTreeService, never()).requireAccessible(any(), any());
    }

    @Test
    void shouldRestoreOnlyDeletedTargetWhenParentAccessible() {
        mockTransactionTemplate();
        FolderEntity deleted = FolderEntity.builder().id(2L).workspaceId(10L).parentId(1L).name("项目")
                .root(false).status(FolderStatusVO.DELETED).deletedAt(LocalDateTime.now()).build();
        FolderEntity root = folder(1L, null, true, "根目录", FolderStatusVO.ACTIVE);
        when(folderRepository.findById(2L)).thenReturn(Optional.of(deleted));
        when(folderTreeService.requireAccessible(10L, 1L)).thenReturn(root);
        when(folderRepository.findActiveByParentIdAndName(10L, 1L, "项目")).thenReturn(Optional.empty());

        FolderEntity restored = folderService.restoreFolder(10L, 2L);

        assertEquals(FolderStatusVO.ACTIVE, restored.getStatus());
        assertNull(restored.getDeletedAt());
        verify(folderRepository).update(restored);
    }

    @Test
    void shouldNotUpdateWhenMovingToCurrentParent() {
        mockTransactionTemplate();
        FolderEntity folder = folder(2L, 1L, false, "项目", FolderStatusVO.ACTIVE);
        FolderEntity parent = folder(1L, null, true, "根目录", FolderStatusVO.ACTIVE);
        when(folderTreeService.requireAccessible(10L, 2L)).thenReturn(folder);
        when(folderTreeService.requireAccessible(10L, 1L)).thenReturn(parent);

        FolderEntity result = folderService.moveFolder(10L, 2L, 1L);

        assertEquals(1L, result.getParentId());
        verify(folderRepository, never()).update(any());
    }

    @SuppressWarnings("unchecked")
    private void mockTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
    }

    private FolderEntity folder(Long id, Long parentId, boolean root, String name, FolderStatusVO status) {
        return FolderEntity.builder().id(id).workspaceId(10L).parentId(parentId).name(name).root(root).status(status).build();
    }
}
