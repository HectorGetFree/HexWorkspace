package com.hector.domain.workspace.service.impl;

import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.workspace.adapter.port.IWorkspaceCreationLock;
import com.hector.domain.folder.service.IFolderService;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.model.valobj.WorkspaceStatusVO;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import com.hector.domain.workspace.service.IWorkspaceService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.List;
import java.util.UUID;

/** Workspace 领域服务实现。 */
@Service
public class WorkspaceServiceImpl implements IWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceServiceImpl.class);
    private static final String WORKSPACE_CODE_PREFIX = "ws_";
    private static final int WORKSPACE_CODE_RETRY_TIMES = 5;

    @Resource
    private IWorkspaceRepository workspaceRepository;

    @Resource
    private IUserRepository userRepository;

    @Resource
    private IFolderService folderService;

    @Resource
    private IWorkspaceCreationLock workspaceCreationLock;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Override
    public WorkspaceEntity ensurePersonalWorkspace(Long ownerUserId, String ownerUsername) {
        // 1. 统一入口参数，避免后续查询和持久化使用不合法的用户身份或展示名称。
        Long normalizedOwnerUserId = normalizeOwnerUserId(ownerUserId);
        String normalizedOwnerUsername = normalizeOwnerUsername(ownerUsername);
        log.info("Workspace领域服务-确保个人Workspace开始 ownerUserId:{} username:{}",
                normalizedOwnerUserId, normalizedOwnerUsername);

        // 2. 先取得用户粒度的分布式锁，并保持到当前事务完成；并发调用只会复用同一个 Workspace。
        workspaceCreationLock.lock(normalizedOwnerUserId);
        boolean releaseAfterTransaction = registerLockReleaseAfterTransaction(normalizedOwnerUserId);
        try {
            // 3. Workspace 与系统根目录必须在同一个编码式事务中创建或共同回滚。
            WorkspaceEntity workspace = executeInTransaction(() -> ensurePersonalWorkspaceInTransaction(
                    normalizedOwnerUserId, normalizedOwnerUsername));
            log.info("Workspace领域服务-确保个人Workspace完成 workspaceId:{} workspaceCode:{} ownerUserId:{}",
                    workspace.getId(), workspace.getWorkspaceCode(), normalizedOwnerUserId);
            return workspace;
        } finally {
            if (!releaseAfterTransaction) workspaceCreationLock.unlock(normalizedOwnerUserId);
        }
    }

    @Override
    public WorkspaceEntity getActiveWorkspace(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_WORKSPACE_ID);
        }
        WorkspaceEntity workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new BusinessException(ResponseCode.WORKSPACE_NOT_FOUND));
        if (!workspace.isActive()) {
            throw new BusinessException(ResponseCode.WORKSPACE_STATUS_UNAVAILABLE);
        }
        return workspace;
    }

    @Override
    public WorkspaceEntity assertOwner(Long workspaceId, Long userId) {
        Long normalizedUserId = normalizeOwnerUserId(userId);
        WorkspaceEntity workspace = getActiveWorkspace(workspaceId);
        if (!normalizedUserId.equals(workspace.getOwnerUserId())) {
            throw new BusinessException(ResponseCode.WORKSPACE_OWNER_REQUIRED);
        }
        return workspace;
    }

    @Override
    public List<WorkspaceEntity> listOwnedWorkspaces(Long ownerUserId) {
        Long normalizedOwnerUserId = normalizeOwnerUserId(ownerUserId);
        // 查询前先校验用户有效，避免已失效账号继续读取私有资料空间。
        getActiveOwner(normalizedOwnerUserId);
        return workspaceRepository.listByOwnerUserId(normalizedOwnerUserId);
    }

    private Long normalizeOwnerUserId(Long ownerUserId) {
        if (ownerUserId == null || ownerUserId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_OWNER_USER_ID);
        }
        return ownerUserId;
    }

    private String normalizeOwnerUsername(String ownerUsername) {
        if (ownerUsername == null || ownerUsername.trim().isEmpty()) {
            throw new BusinessException(ResponseCode.EMPTY_USERNAME);
        }
        return ownerUsername.trim();
    }

    private UserEntity getActiveOwner(Long ownerUserId) {
        UserEntity ownerUser = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));
        if (!ownerUser.isActive()) {
            throw new BusinessException(ResponseCode.USER_STATUS_UNAVAILABLE);
        }
        return ownerUser;
    }

    private String generateWorkspaceCode() {
        for (int i = 0; i < WORKSPACE_CODE_RETRY_TIMES; i++) {
            String workspaceCode = WORKSPACE_CODE_PREFIX + UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 12)
                    .toLowerCase(Locale.ROOT);
            if (workspaceRepository.findByWorkspaceCode(workspaceCode).isEmpty()) {
                return workspaceCode;
            }
        }
        throw new BusinessException(ResponseCode.SYSTEM_ERROR);
    }

    /** 在已持有创建锁的事务内创建默认 Workspace 与其唯一系统根目录。 */
    private WorkspaceEntity ensurePersonalWorkspaceInTransaction(Long ownerUserId, String ownerUsername) {
        WorkspaceEntity existing = workspaceRepository.findDefaultByOwnerUserId(ownerUserId).orElse(null);
        if (existing != null) {
            folderService.ensureRootFolder(existing.getId());
            return existing;
        }

        UserEntity owner = getActiveOwner(ownerUserId);
        LocalDateTime now = LocalDateTime.now();
        WorkspaceEntity saved = workspaceRepository.save(WorkspaceEntity.builder()
                .workspaceCode(generateWorkspaceCode()).workspaceName(ownerUsername + " 的个人资料库")
                .isDefault(true).status(WorkspaceStatusVO.ACTIVE).ownerUserId(owner.getId())
                .createdAt(now).updatedAt(now).build());
        folderService.ensureRootFolder(saved.getId());
        return saved;
    }

    /** 使用 TransactionTemplate 显式定义 Workspace 与根目录的 MySQL 事务边界。 */
    private WorkspaceEntity executeInTransaction(java.util.function.Supplier<WorkspaceEntity> action) {
        WorkspaceEntity result = transactionTemplate.execute(status -> action.get());
        if (result == null) throw new BusinessException(ResponseCode.SYSTEM_ERROR);
        return result;
    }

    /** 若外层已有事务，锁必须延迟到提交或回滚后才释放，避免提交前被并发请求穿透。 */
    private boolean registerLockReleaseAfterTransaction(Long ownerUserId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return false;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                workspaceCreationLock.unlock(ownerUserId);
            }
        });
        return true;
    }
}
