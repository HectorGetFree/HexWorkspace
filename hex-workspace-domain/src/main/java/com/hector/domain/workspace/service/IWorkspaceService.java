package com.hector.domain.workspace.service;

import com.hector.domain.workspace.model.entity.WorkspaceEntity;

import java.util.List;

/** Workspace 领域服务。 */
public interface IWorkspaceService {

    /** 为新注册用户创建默认个人 Workspace；已存在则直接返回。 */
    WorkspaceEntity ensurePersonalWorkspace(Long ownerUserId, String ownerUsername);

    /** 获取当前可用的 Workspace。 */
    WorkspaceEntity getActiveWorkspace(Long workspaceId);

    /** 校验用户是否为 Workspace Owner；资源创建属于 Owner 的写入能力。 */
    WorkspaceEntity assertOwner(Long workspaceId, Long userId);

    /** 查询当前用户拥有的 Workspace。 */
    List<WorkspaceEntity> listOwnedWorkspaces(Long ownerUserId);
}
