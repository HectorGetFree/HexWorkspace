package com.hector.domain.workspace.service;

import com.hector.domain.workspace.model.entity.WorkspaceEntity;

import java.util.List;

/** Workspace 领域服务。 */
public interface IWorkspaceService {

    /** 为新注册用户创建默认个人 Workspace；已存在则直接返回。 */
    WorkspaceEntity ensurePersonalWorkspace(Long ownerUserId, String ownerUsername);

    /** 创建由当前用户拥有的团队 Workspace。 */
    WorkspaceEntity createTeamWorkspace(String workspaceName, Long ownerUserId, String description);

    /** 查询当前用户拥有的个人和团队 Workspace。 */
    List<WorkspaceEntity> listOwnedWorkspaces(Long ownerUserId);
}
