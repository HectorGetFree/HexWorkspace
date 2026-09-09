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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FolderServiceImplTest {

    @Mock
    private IFolderRepository folderRepository;

    @InjectMocks
    private FolderServiceImpl folderService;

    @Test
    void shouldRejectDuplicateFolderNameUnderSameParent() {
        FolderEntity parent = folder(1L, null, true, "根目录");
        when(folderRepository.findById(1L)).thenReturn(Optional.of(parent));
        when(folderRepository.findActiveByParentIdAndName(10L, 1L, "项目"))
                .thenReturn(Optional.of(folder(2L, 1L, false, "项目")));

        assertThrows(BusinessException.class, () -> folderService.createFolder(10L, 1L, "项目"));
    }

    @Test
    void shouldRejectMovingFolderIntoItsDescendant() {
        FolderEntity source = folder(2L, 1L, false, "项目");
        FolderEntity child = folder(3L, 2L, false, "子目录");
        when(folderRepository.findById(2L)).thenReturn(Optional.of(source));
        when(folderRepository.findById(3L)).thenReturn(Optional.of(child));

        assertThrows(BusinessException.class, () -> folderService.moveFolder(10L, 2L, 3L));
    }

    private FolderEntity folder(Long id, Long parentId, boolean root, String name) {
        return FolderEntity.builder()
                .id(id)
                .workspaceId(10L)
                .parentId(parentId)
                .name(name)
                .root(root)
                .status(FolderStatusVO.ACTIVE)
                .build();
    }
}
