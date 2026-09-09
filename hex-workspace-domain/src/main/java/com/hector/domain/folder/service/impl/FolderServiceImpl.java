package com.hector.domain.folder.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.model.valobj.FolderStatusVO;
import com.hector.domain.folder.repository.IFolderRepository;
import com.hector.domain.folder.service.IFolderService;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文件夹领域服务实现。
 * 负责维护 Workspace 目录树结构，以及目录树删除时的资源一致性。
 */
@Service
public class FolderServiceImpl implements IFolderService {

    private static final Logger log = LoggerFactory.getLogger(FolderServiceImpl.class);

    @Resource
    private IFolderRepository folderRepository;

    @Resource
    private IWorkspaceRepository workspaceRepository;

    @Resource
    private IDocumentRepository documentRepository;

    @Override
    @Transactional
    public FolderEntity ensureRootFolder(Long workspaceId) {
        log.info("文件夹领域服务-确保根目录开始 workspaceId:{}", workspaceId);

        // 1. 幂等查询根目录，确保注册重试不会产生第二个根节点。
        FolderEntity existing = folderRepository.findRootByWorkspaceId(workspaceId).orElse(null);
        if (existing != null) {
            log.info("文件夹领域服务-根目录已存在 workspaceId:{} folderId:{}", workspaceId, existing.getId());
            return existing;
        }

        // 2. 根目录只能依附于真实 Workspace，由数据库唯一索引提供最终并发保护。
        workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new BusinessException(ResponseCode.WORKSPACE_NOT_FOUND));

        // 3. 创建唯一根目录，供后续文件夹和文档统一挂载。
        LocalDateTime now = LocalDateTime.now();
        FolderEntity rootFolder = folderRepository.save(FolderEntity.builder()
                .folderCode(generateFolderCode())
                .workspaceId(workspaceId)
                .name("根目录")
                .root(true)
                .status(FolderStatusVO.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        log.info("文件夹领域服务-确保根目录完成 workspaceId:{} folderId:{}", workspaceId, rootFolder.getId());
        return rootFolder;
    }

    @Override
    @Transactional
    public FolderEntity createFolder(Long workspaceId, Long parentId, String name) {
        log.info("文件夹领域服务-新建文件夹开始 workspaceId:{} parentId:{}", workspaceId, parentId);
        FolderEntity parent = getActiveFolder(workspaceId, parentId);
        String normalized = normalizeName(name);
        assertNameAvailable(workspaceId, parent.getId(), normalized, null);

        LocalDateTime now = LocalDateTime.now();
        FolderEntity folder = folderRepository.save(FolderEntity.builder()
                .folderCode(generateFolderCode())
                .workspaceId(workspaceId)
                .parentId(parent.getId())
                .name(normalized)
                .root(false)
                .status(FolderStatusVO.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        log.info("文件夹领域服务-新建文件夹完成 workspaceId:{} folderId:{} parentId:{}",
                workspaceId, folder.getId(), folder.getParentId());
        return folder;
    }

    @Override
    public FolderEntity getActiveFolder(Long workspaceId, Long folderId) {
        FolderEntity folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new BusinessException(ResponseCode.FOLDER_NOT_FOUND));
        if (!workspaceId.equals(folder.getWorkspaceId()) || !folder.isActive()) {
            throw new BusinessException(ResponseCode.FOLDER_NOT_FOUND);
        }
        return folder;
    }

    @Override
    public List<FolderEntity> listChildren(Long workspaceId, Long parentId) {
        log.info("文件夹领域服务-查询子目录开始 workspaceId:{} parentId:{}", workspaceId, parentId);
        getActiveFolder(workspaceId, parentId);
        List<FolderEntity> folders = folderRepository.listActiveByParentId(workspaceId, parentId);
        log.info("文件夹领域服务-查询子目录完成 workspaceId:{} parentId:{} folderCount:{}",
                workspaceId, parentId, folders.size());
        return folders;
    }

    @Override
    @Transactional
    public FolderEntity renameFolder(Long workspaceId, Long folderId, String name) {
        log.info("文件夹领域服务-重命名文件夹开始 workspaceId:{} folderId:{}", workspaceId, folderId);
        FolderEntity folder = mutableFolder(workspaceId, folderId);
        String normalized = normalizeName(name);
        assertNameAvailable(workspaceId, folder.getParentId(), normalized, folder.getId());
        folder.rename(normalized);
        folderRepository.update(folder);
        log.info("文件夹领域服务-重命名文件夹完成 workspaceId:{} folderId:{} name:{}",
                workspaceId, folderId, normalized);
        return folder;
    }

    @Override
    @Transactional
    public FolderEntity moveFolder(Long workspaceId, Long folderId, Long targetParentId) {
        log.info("文件夹领域服务-移动文件夹开始 workspaceId:{} folderId:{} targetParentId:{}",
                workspaceId, folderId, targetParentId);

        // 1. 校验源目录与目标目录都在当前 Workspace，且根目录不可移动。
        FolderEntity folder = mutableFolder(workspaceId, folderId);
        FolderEntity target = getActiveFolder(workspaceId, targetParentId);

        // 2. 从目标向根回溯，阻止移动到自身或后代节点形成目录环。
        FolderEntity cursor = target;
        while (cursor != null) {
            if (cursor.getId().equals(folder.getId())) {
                throw new BusinessException(ResponseCode.FOLDER_MOVE_CYCLE);
            }
            cursor = cursor.getParentId() == null ? null : getActiveFolder(workspaceId, cursor.getParentId());
        }

        // 3. 在目标目录执行重名校验后更新父节点。
        assertNameAvailable(workspaceId, target.getId(), folder.getName(), folder.getId());
        folder.moveTo(target.getId());
        folderRepository.update(folder);
        log.info("文件夹领域服务-移动文件夹完成 workspaceId:{} folderId:{} targetParentId:{}",
                workspaceId, folderId, targetParentId);
        return folder;
    }

    @Override
    @Transactional
    public void deleteFolderTree(Long workspaceId, Long folderId) {
        log.info("文件夹领域服务-删除目录树开始 workspaceId:{} folderId:{}", workspaceId, folderId);
        FolderEntity root = mutableFolder(workspaceId, folderId);

        // 1. 深度优先删除子目录，确保整棵目录树在同一事务内退出常规查询。
        for (FolderEntity child : folderRepository.listActiveByParentId(workspaceId, root.getId())) {
            deleteFolderTree(workspaceId, child.getId());
        }

        // 2. 将当前目录的云文档一并逻辑删除，底层版本对象保留以支持未来恢复。
        for (DocumentEntity document : documentRepository.listActiveByParentId(workspaceId, root.getId())) {
            document.delete();
            documentRepository.update(document);
        }

        // 3. 最后删除目录节点，避免事务内出现仍可见但父目录已消失的资源。
        root.delete();
        folderRepository.update(root);
        log.info("文件夹领域服务-删除目录树完成 workspaceId:{} folderId:{}", workspaceId, folderId);
    }

    private FolderEntity mutableFolder(Long workspaceId, Long id) {
        FolderEntity folder = getActiveFolder(workspaceId, id);
        if (folder.isRoot()) {
            throw new BusinessException(ResponseCode.ROOT_FOLDER_IMMUTABLE);
        }
        return folder;
    }

    private void assertNameAvailable(Long workspaceId, Long parentId, String name, Long selfId) {
        folderRepository.findActiveByParentIdAndName(workspaceId, parentId, name)
                .filter(found -> !found.getId().equals(selfId))
                .ifPresent(found -> {
                    throw new BusinessException(ResponseCode.FOLDER_NAME_DUPLICATE);
                });
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ResponseCode.EMPTY_FOLDER_NAME);
        }
        return name.trim();
    }

    private String generateFolderCode() {
        return "fld_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
