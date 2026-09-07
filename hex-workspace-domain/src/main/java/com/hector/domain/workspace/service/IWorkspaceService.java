package com.hector.domain.workspace.service;

import com.hector.domain.workspace.model.entity.WorkspaceEntity;

/** Workspace 领域服务。 */
public interface IWorkspaceService {

    /** 为新注册用户创建默认个人 Workspace；已存在则直接返回。 */
    WorkspaceEntity ensurePersonalWorkspace(Long ownerUserId, String ownerUsername);
}
