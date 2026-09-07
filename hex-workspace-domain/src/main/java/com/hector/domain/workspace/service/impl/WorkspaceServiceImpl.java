package com.hector.domain.workspace.service.impl;

import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.model.valobj.WorkspaceStatusVO;
import com.hector.domain.workspace.model.valobj.WorkspaceTypeVO;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import com.hector.domain.workspace.service.IWorkspaceService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
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

    @Override
    public WorkspaceEntity ensurePersonalWorkspace(Long ownerUserId, String ownerUsername) {
        // 1. 统一入口参数，避免后续查询和持久化使用不合法的用户身份或展示名称。
        Long normalizedOwnerUserId = normalizeOwnerUserId(ownerUserId);
        String normalizedOwnerUsername = normalizeOwnerUsername(ownerUsername);
        log.info("Workspace领域服务-确保个人Workspace开始 ownerUserId:{} username:{}",
                normalizedOwnerUserId, normalizedOwnerUsername);

        // 2. 先按“拥有者 + PERSONAL 类型”查询，保证注册重试不会为同一用户重复创建资料库。
        WorkspaceEntity existingWorkspace = workspaceRepository
                .findByOwnerUserIdAndType(normalizedOwnerUserId, WorkspaceTypeVO.PERSONAL)
                .orElse(null);
        if (existingWorkspace != null) {
            return existingWorkspace;
        }

        // 3. 校验拥有者仍存在且可用，避免为无效账号创建无法管理的 Workspace。
        UserEntity ownerUser = userRepository.findById(normalizedOwnerUserId)
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));
        if (!ownerUser.isActive()) {
            throw new BusinessException(ResponseCode.USER_STATUS_UNAVAILABLE);
        }

        // 4. 构建个人 Workspace 的初始快照；PERSONAL 类型和 ACTIVE 状态由领域规则固定。
        LocalDateTime now = LocalDateTime.now();
        WorkspaceEntity workspaceEntity = WorkspaceEntity.builder()
                .workspaceCode(generateWorkspaceCode())
                .workspaceName(normalizedOwnerUsername + " 的个人资料库")
                .type(WorkspaceTypeVO.PERSONAL)
                .status(WorkspaceStatusVO.ACTIVE)
                .ownerUserId(normalizedOwnerUserId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        // 5. 持久化后返回带主键的聚合，外层注册事务负责与用户创建一起提交或回滚。
        WorkspaceEntity savedWorkspace = workspaceRepository.save(workspaceEntity);
        log.info("Workspace领域服务-创建个人Workspace完成 workspaceId:{} workspaceCode:{} ownerUserId:{}",
                savedWorkspace.getId(), savedWorkspace.getWorkspaceCode(), normalizedOwnerUserId);
        return savedWorkspace;
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
