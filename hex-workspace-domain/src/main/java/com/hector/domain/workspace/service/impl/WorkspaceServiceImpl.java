package com.hector.domain.workspace.service.impl;

import com.hector.domain.user.model.entity.UserEntity;
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
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    public WorkspaceEntity ensurePersonalWorkspace(Long ownerUserId, String ownerUsername) {
        // 1. 统一入口参数，避免后续查询和持久化使用不合法的用户身份或展示名称。
        Long normalizedOwnerUserId = normalizeOwnerUserId(ownerUserId);
        String normalizedOwnerUsername = normalizeOwnerUsername(ownerUsername);
        log.info("Workspace领域服务-确保个人Workspace开始 ownerUserId:{} username:{}",
                normalizedOwnerUserId, normalizedOwnerUsername);

        // 2. 先按拥有者查询默认资料库，保证注册重试不会为同一用户重复创建默认 Workspace。
        WorkspaceEntity existingWorkspace = workspaceRepository
                .findDefaultByOwnerUserId(normalizedOwnerUserId)
                .orElse(null);
        if (existingWorkspace != null) {
            folderService.ensureRootFolder(existingWorkspace.getId());
            return existingWorkspace;
        }

        // 3. 校验拥有者仍存在且可用，避免为无效账号创建无法管理的 Workspace。
        UserEntity ownerUser = userRepository.findById(normalizedOwnerUserId)
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));
        if (!ownerUser.isActive()) {
            throw new BusinessException(ResponseCode.USER_STATUS_UNAVAILABLE);
        }

        // 4. 构建默认资料库快照；默认标识和 ACTIVE 状态由领域规则固定。
        LocalDateTime now = LocalDateTime.now();
        WorkspaceEntity workspaceEntity = WorkspaceEntity.builder()
                .workspaceCode(generateWorkspaceCode())
                .workspaceName(normalizedOwnerUsername + " 的个人资料库")
                .isDefault(true)
                .status(WorkspaceStatusVO.ACTIVE)
                .ownerUserId(normalizedOwnerUserId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        // 5. 持久化后返回带主键的聚合，外层注册事务负责与用户创建一起提交或回滚。
        WorkspaceEntity savedWorkspace = workspaceRepository.save(workspaceEntity);
        folderService.ensureRootFolder(savedWorkspace.getId());
        log.info("Workspace领域服务-创建个人Workspace完成 workspaceId:{} workspaceCode:{} ownerUserId:{}",
                savedWorkspace.getId(), savedWorkspace.getWorkspaceCode(), normalizedOwnerUserId);
        return savedWorkspace;
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
}
