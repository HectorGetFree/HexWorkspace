package com.hector.domain.workspace.service;

import com.hector.domain.workspace.model.valobj.WorkspaceStructureResultVO;

/** 提供当前用户在 Workspace 中可见的一级目录结构。 */
public interface IWorkspaceStructureService {

    /**
     * 查询指定目录的直属资源；folderId 为空时查询 Workspace 根目录。
     *
     * @param userId 当前可信用户
     * @param workspaceId 当前可信 Workspace
     * @param folderId 目标目录，为空表示根目录
     * @param limit 返回上限
     * @return 稳定排序后的直属资源
     */
    WorkspaceStructureResultVO listChildren(Long userId, Long workspaceId, Long folderId, int limit);
}
