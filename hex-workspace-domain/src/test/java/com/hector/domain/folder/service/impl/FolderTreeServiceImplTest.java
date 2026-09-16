package com.hector.domain.folder.service.impl;

import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.model.valobj.FolderStatusVO;
import com.hector.domain.folder.repository.IFolderRepository;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FolderTreeServiceImplTest {

    @Mock
    private IFolderRepository folderRepository;

    @InjectMocks
    private FolderTreeServiceImpl folderTreeService;

    @Test
    void shouldAllowFolderWithCompleteActiveAncestorChain() {
        FolderEntity child = folder(3L, 2L, false, FolderStatusVO.ACTIVE);
        FolderEntity parent = folder(2L, 1L, false, FolderStatusVO.ACTIVE);
        FolderEntity root = folder(1L, null, true, FolderStatusVO.ACTIVE);
        when(folderRepository.listAncestorChain(3L)).thenReturn(List.of(child, parent, root));

        assertEquals(child, folderTreeService.requireAccessible(10L, 3L));
    }

    @Test
    void shouldRejectActiveFolderUnderDeletedAncestor() {
        FolderEntity child = folder(3L, 2L, false, FolderStatusVO.ACTIVE);
        FolderEntity deletedParent = folder(2L, 1L, false, FolderStatusVO.DELETED);
        FolderEntity root = folder(1L, null, true, FolderStatusVO.ACTIVE);
        when(folderRepository.listAncestorChain(3L)).thenReturn(List.of(child, deletedParent, root));

        assertThrows(BusinessException.class, () -> folderTreeService.requireAccessible(10L, 3L));
    }

    @Test
    void shouldRejectBrokenAncestorChainWithoutRoot() {
        FolderEntity child = folder(3L, 99L, false, FolderStatusVO.ACTIVE);
        when(folderRepository.listAncestorChain(3L)).thenReturn(List.of(child));

        assertThrows(BusinessException.class, () -> folderTreeService.requireAccessible(10L, 3L));
    }

    private FolderEntity folder(Long id, Long parentId, boolean root, FolderStatusVO status) {
        return FolderEntity.builder().id(id).workspaceId(10L).parentId(parentId).root(root).status(status).build();
    }
}
