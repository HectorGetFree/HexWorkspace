package com.hector.domain.workspace.service.impl;

import com.hector.domain.folder.service.IFolderService;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.model.valobj.UserStatusVO;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.domain.workspace.adapter.port.IWorkspaceCreationLock;
import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 个人 Workspace 与系统根目录的原子初始化测试。 */
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceImplTest {

    @Mock private IWorkspaceRepository workspaceRepository;
    @Mock private IUserRepository userRepository;
    @Mock private IFolderService folderService;
    @Mock private IWorkspaceCreationLock workspaceCreationLock;
    @Mock private TransactionTemplate transactionTemplate;
    @InjectMocks private WorkspaceServiceImpl workspaceService;

    @Test
    void shouldCreateRootFolderInsidePersonalWorkspaceInitialization() {
        mockTransactionTemplate();
        when(workspaceRepository.findDefaultByOwnerUserId(1L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(UserEntity.builder()
                .id(1L).username("hector").status(UserStatusVO.ACTIVE).build()));
        when(workspaceRepository.findByWorkspaceCode(any())).thenReturn(Optional.empty());
        when(workspaceRepository.save(any())).thenReturn(WorkspaceEntity.builder().id(10L).workspaceCode("ws_test").build());

        WorkspaceEntity workspace = workspaceService.ensurePersonalWorkspace(1L, "hector");

        assertEquals(10L, workspace.getId());
        verify(workspaceCreationLock).lock(1L);
        verify(folderService).ensureRootFolder(10L);
        verify(workspaceCreationLock).unlock(1L);
    }

    @SuppressWarnings("unchecked")
    private void mockTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
    }
}
