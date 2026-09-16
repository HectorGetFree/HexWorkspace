package com.hector.domain.folder.service.impl;

import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.model.valobj.FolderStatusVO;
import com.hector.domain.folder.repository.IFolderRepository;
import com.hector.domain.folder.service.IFolderService;
import com.hector.domain.folder.service.IFolderTreeLock;
import com.hector.domain.folder.service.IFolderTreeService;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 文件夹领域服务。
 *
 * <p>负责目录树元数据变更。删除只标记目标目录，后代资源是否可用统一交由
 * {@link IFolderTreeService} 根据祖先链动态判断。</p>
 */
@Service
public class FolderServiceImpl implements IFolderService {

    private static final Logger log = LoggerFactory.getLogger(FolderServiceImpl.class);
    private static final int FOLDER_NAME_MAX_LENGTH = 255;

    @Resource
    private IFolderRepository folderRepository;

    @Resource
    private IWorkspaceRepository workspaceRepository;

    @Resource
    private IFolderTreeService folderTreeService;

    @Resource
    private IFolderTreeLock folderTreeLock;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Override
    public FolderEntity ensureRootFolder(Long workspaceId) {
        return executeWithTreeLock(workspaceId, () -> executeInTransaction(() -> {
            log.info("文件夹领域服务-确保根目录开始 workspaceId:{}", workspaceId);
            // 1. 在目录树锁内复用既有根节点，防止重试创建第二个 Root。
            FolderEntity existing = folderRepository.findRootByWorkspaceId(workspaceId).orElse(null);
            if (existing != null) {
                if (!existing.isActive() || existing.getParentId() != null || existing.getDeletedAt() != null) {
                    throw new BusinessException(ResponseCode.SYSTEM_ERROR);
                }
                return existing;
            }
            // 2. Root 只能属于真实 Workspace，且不从用户输入继承父目录信息。
            workspaceRepository.findById(workspaceId)
                    .orElseThrow(() -> new BusinessException(ResponseCode.WORKSPACE_NOT_FOUND));
            // 3. root_workspace_id 生成列的唯一约束与目录树锁共同保证每个 Workspace 最多一个 Root。
            LocalDateTime now = LocalDateTime.now();
            FolderEntity rootFolder = folderRepository.save(FolderEntity.builder()
                    .folderCode(generateFolderCode())
                    .workspaceId(workspaceId)
                    .parentId(null)
                    .name("根目录")
                    .root(true)
                    .status(FolderStatusVO.ACTIVE)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            log.info("文件夹领域服务-确保根目录完成 workspaceId:{} folderId:{}", workspaceId, rootFolder.getId());
            return rootFolder;
        }));
    }

    @Override
    public FolderEntity createFolder(Long workspaceId, Long parentId, String name) {
        return executeWithTreeLock(workspaceId, () -> executeInTransaction(() -> {
            log.info("文件夹领域服务-新建文件夹开始 workspaceId:{} parentId:{}", workspaceId, parentId);
            // 1. 父目录必须可访问；Workspace 归属由父目录推导，不信任调用方的目录归属。
            FolderEntity parent = folderTreeService.requireAccessible(workspaceId, parentId);
            String normalized = normalizeName(name);
            assertNameAvailable(parent.getWorkspaceId(), parent.getId(), normalized, null);
            // 2. 新节点只写入父目录已确认的 Workspace，保证整条祖先链归属一致。
            LocalDateTime now = LocalDateTime.now();
            FolderEntity folder = folderRepository.save(FolderEntity.builder()
                    .folderCode(generateFolderCode())
                    .workspaceId(parent.getWorkspaceId())
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
        }));
    }

    @Override
    public FolderEntity getActiveFolder(Long workspaceId, Long folderId) {
        return folderTreeService.requireAccessible(workspaceId, folderId);
    }

    @Override
    public List<FolderEntity> listChildren(Long workspaceId, Long parentId) {
        log.info("文件夹领域服务-查询子目录开始 workspaceId:{} parentId:{}", workspaceId, parentId);
        // 已确认父目录可访问，直属 ACTIVE 子目录无需逐个重复查询祖先链。
        folderTreeService.requireAccessible(workspaceId, parentId);
        List<FolderEntity> folders = folderRepository.listActiveByParentId(workspaceId, parentId);
        log.info("文件夹领域服务-查询子目录完成 workspaceId:{} parentId:{} folderCount:{}", workspaceId, parentId, folders.size());
        return folders;
    }

    @Override
    public FolderEntity renameFolder(Long workspaceId, Long folderId, String name) {
        return executeWithTreeLock(workspaceId, () -> executeInTransaction(() -> {
            log.info("文件夹领域服务-重命名文件夹开始 workspaceId:{} folderId:{}", workspaceId, folderId);
            FolderEntity folder = mutableAccessibleFolder(workspaceId, folderId);
            String normalized = normalizeName(name);
            if (normalized.equals(folder.getName())) {
                return folder;
            }
            assertNameAvailable(workspaceId, folder.getParentId(), normalized, folder.getId());
            folder.rename(normalized);
            folderRepository.update(folder);
            log.info("文件夹领域服务-重命名文件夹完成 workspaceId:{} folderId:{} name:{}", workspaceId, folderId, normalized);
            return folder;
        }));
    }

    @Override
    public FolderEntity moveFolder(Long workspaceId, Long folderId, Long targetParentId) {
        return executeWithTreeLock(workspaceId, () -> executeInTransaction(() -> {
            log.info("文件夹领域服务-移动文件夹开始 workspaceId:{} folderId:{} targetParentId:{}", workspaceId, folderId, targetParentId);
            // 1. 源节点与目标节点均要可访问，阻止从已删除子树中借由 Move 脱离。
            FolderEntity folder = mutableAccessibleFolder(workspaceId, folderId);
            FolderEntity target = folderTreeService.requireAccessible(workspaceId, targetParentId);
            if (folder.getParentId().equals(target.getId())) {
                return folder;
            }
            // 2. 目标的祖先链中出现源节点即会形成环；目录树锁避免交叉 Move 的并发成环。
            if (folderTreeService.hasAncestor(workspaceId, target.getId(), folder.getId())) {
                throw new BusinessException(ResponseCode.FOLDER_MOVE_CYCLE);
            }
            // 3. Move 只修改源节点 parentId，不改写任何后代目录、文档或存储对象。
            assertNameAvailable(workspaceId, target.getId(), folder.getName(), folder.getId());
            folder.moveTo(target.getId());
            folderRepository.update(folder);
            log.info("文件夹领域服务-移动文件夹完成 workspaceId:{} folderId:{} targetParentId:{}", workspaceId, folderId, targetParentId);
            return folder;
        }));
    }

    @Override
    public FolderEntity deleteFolder(Long workspaceId, Long folderId) {
        return executeWithTreeLock(workspaceId, () -> executeInTransaction(() -> {
            log.info("文件夹领域服务-删除文件夹开始 workspaceId:{} folderId:{}", workspaceId, folderId);
            FolderEntity folder = getFolderInWorkspace(workspaceId, folderId);
            assertNotRoot(folder);
            // 1. 重复删除直接返回，严格保留首次 deletedAt 供未来 GC 计算保留期。
            if (folder.isDeleted()) {
                return folder;
            }
            // 2. 后代状态保持不变；仅删除入口节点即可使整个子树动态不可访问。
            folderTreeService.requireAccessible(workspaceId, folderId);
            folder.delete();
            folderRepository.update(folder);
            log.info("文件夹领域服务-删除文件夹完成 workspaceId:{} folderId:{}", workspaceId, folderId);
            return folder;
        }));
    }

    @Override
    public FolderEntity restoreFolder(Long workspaceId, Long folderId) {
        return executeWithTreeLock(workspaceId, () -> executeInTransaction(() -> {
            log.info("文件夹领域服务-恢复文件夹开始 workspaceId:{} folderId:{}", workspaceId, folderId);
            FolderEntity folder = getFolderInWorkspace(workspaceId, folderId);
            assertNotRoot(folder);
            if (!folder.isDeleted()) {
                throw new BusinessException(ResponseCode.FOLDER_NOT_FOUND);
            }
            // 1. 仅恢复用户明确指定的节点；父节点不可访问时不能跳过祖先直接恢复。
            FolderEntity parent = folderTreeService.requireAccessible(workspaceId, folder.getParentId());
            // 2. 已删除目录不占用命名空间，但恢复时必须重新满足 ACTIVE 同级唯一约束。
            assertNameAvailable(parent.getWorkspaceId(), parent.getId(), folder.getName(), folder.getId());
            folder.restore();
            folderRepository.update(folder);
            log.info("文件夹领域服务-恢复文件夹完成 workspaceId:{} folderId:{}", workspaceId, folderId);
            return folder;
        }));
    }

    private FolderEntity mutableAccessibleFolder(Long workspaceId, Long folderId) {
        FolderEntity folder = folderTreeService.requireAccessible(workspaceId, folderId);
        assertNotRoot(folder);
        return folder;
    }

    private FolderEntity getFolderInWorkspace(Long workspaceId, Long folderId) {
        if (workspaceId == null || workspaceId <= 0 || folderId == null || folderId <= 0) {
            throw new BusinessException(ResponseCode.FOLDER_NOT_FOUND);
        }
        FolderEntity folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new BusinessException(ResponseCode.FOLDER_NOT_FOUND));
        if (!workspaceId.equals(folder.getWorkspaceId())) {
            throw new BusinessException(ResponseCode.FOLDER_NOT_FOUND);
        }
        return folder;
    }

    private void assertNotRoot(FolderEntity folder) {
        if (folder.isRoot()) {
            throw new BusinessException(ResponseCode.ROOT_FOLDER_IMMUTABLE);
        }
    }

    private void assertNameAvailable(Long workspaceId, Long parentId, String name, Long selfId) {
        folderRepository.findActiveByParentIdAndName(workspaceId, parentId, name)
                .filter(found -> selfId == null || !found.getId().equals(selfId))
                .ifPresent(found -> { throw new BusinessException(ResponseCode.FOLDER_NAME_DUPLICATE); });
    }

    private String normalizeName(String name) {
        String normalized = name == null ? null : name.trim();
        if (normalized == null || normalized.isEmpty()) {
            throw new BusinessException(ResponseCode.EMPTY_FOLDER_NAME);
        }
        if (".".equals(normalized) || "..".equals(normalized) || normalized.length() > FOLDER_NAME_MAX_LENGTH) {
            throw new BusinessException(ResponseCode.ILLEGAL_PARAMETER);
        }
        return normalized;
    }

    private <T> T executeWithTreeLock(Long workspaceId, Supplier<T> action) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new BusinessException(ResponseCode.WORKSPACE_NOT_FOUND);
        }
        folderTreeLock.lock(workspaceId);
        try {
            return action.get();
        } finally {
            folderTreeLock.unlock(workspaceId);
        }
    }

    private <T> T executeInTransaction(Supplier<T> action) {
        T result = transactionTemplate.execute(status -> action.get());
        if (result == null) {
            throw new BusinessException(ResponseCode.SYSTEM_ERROR);
        }
        return result;
    }

    private String generateFolderCode() {
        return "fld_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
