package com.hector.domain.folder.service.impl;

import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.repository.IFolderRepository;
import com.hector.domain.folder.service.IFolderTreeService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Folder 树的动态可访问性实现。
 *
 * <p>目录删除只标记目标节点，不级联修改后代。因此每次从外部进入树时都要通过祖先链
 * 验证可访问性，避免后代仍为 ACTIVE 时绕过已删除祖先。</p>
 */
@Service
public class FolderTreeServiceImpl implements IFolderTreeService {

    @Resource
    private IFolderRepository folderRepository;

    @Override
    public FolderEntity requireAccessible(Long workspaceId, Long folderId) {
        return requireAccessibleChain(workspaceId, folderId).get(0);
    }

    @Override
    public boolean hasAncestor(Long workspaceId, Long folderId, Long ancestorId) {
        return requireAccessibleChain(workspaceId, folderId).stream()
                .anyMatch(item -> ancestorId.equals(item.getId()));
    }

    private List<FolderEntity> requireAccessibleChain(Long workspaceId, Long folderId) {
        if (workspaceId == null || workspaceId <= 0 || folderId == null || folderId <= 0) {
            throw new BusinessException(ResponseCode.FOLDER_NOT_FOUND);
        }

        // 1. 使用递归查询一次取回完整祖先链，避免逐层查询带来的 N+1 问题。
        List<FolderEntity> chain = folderRepository.listAncestorChain(folderId);
        if (chain.isEmpty()) {
            throw new BusinessException(ResponseCode.FOLDER_NOT_FOUND);
        }

        // 2. 链路中的每个节点都必须属于当前 Workspace 且为 ACTIVE。
        boolean allActiveInWorkspace = chain.stream().allMatch(item -> workspaceId.equals(item.getWorkspaceId())
                && item.isActive());
        if (!allActiveInWorkspace) {
            throw new BusinessException(ResponseCode.FOLDER_NOT_FOUND);
        }

        // 3. 链路必须以当前 Workspace 的合法根目录结束；断链或跨 Workspace 指向均不可访问。
        long validRootCount = chain.stream().filter(item -> item.isRoot()
                        && item.getParentId() == null && item.getDeletedAt() == null
                        && workspaceId.equals(item.getWorkspaceId()))
                .count();
        if (validRootCount != 1L) {
            throw new BusinessException(ResponseCode.FOLDER_NOT_FOUND);
        }
        return chain;
    }
}
