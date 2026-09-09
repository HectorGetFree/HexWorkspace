package com.hector.domain.folder.service;

import com.hector.domain.folder.model.entity.FolderEntity;
import java.util.List;

/** 个人 Workspace 文件夹领域服务。 */
public interface IFolderService {

    /** 幂等创建或返回 Workspace 的唯一根目录。 */
    FolderEntity ensureRootFolder(Long workspaceId);

    /** 在指定父目录下新建文件夹。 */
    FolderEntity createFolder(Long workspaceId, Long parentId, String name);

    /** 查询属于当前 Workspace 的有效文件夹。 */
    FolderEntity getActiveFolder(Long workspaceId, Long folderId);

    /** 查询指定目录的直接子文件夹。 */
    List<FolderEntity> listChildren(Long workspaceId, Long parentId);

    /** 重命名非根文件夹。 */
    FolderEntity renameFolder(Long workspaceId, Long folderId, String name);

    /** 在当前 Workspace 内移动非根文件夹。 */
    FolderEntity moveFolder(Long workspaceId, Long folderId, Long targetParentId);

    /** 递归逻辑删除非根文件夹及其中的云文档。 */
    void deleteFolderTree(Long workspaceId, Long folderId);
}
