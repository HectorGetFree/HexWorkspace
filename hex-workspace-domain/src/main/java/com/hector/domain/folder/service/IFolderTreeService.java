package com.hector.domain.folder.service;

import com.hector.domain.folder.model.entity.FolderEntity;

/** 目录树访问规则服务，集中维护祖先链可访问性与移动防环判断。 */
public interface IFolderTreeService {

    /** 查询并确认目录自身及完整祖先链均处于可访问状态。 */
    FolderEntity requireAccessible(Long workspaceId, Long folderId);

    /** 判断指定目录的祖先链中是否存在某个目录，用于阻止移动形成环。 */
    boolean hasAncestor(Long workspaceId, Long folderId, Long ancestorId);
}
