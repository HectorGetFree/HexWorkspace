package com.hector.domain.folder.repository;

import com.hector.domain.folder.model.entity.FolderEntity;
import java.util.List;
import java.util.Optional;

/** 文件夹聚合仓储。 */
public interface IFolderRepository {

    /** 保存新文件夹并返回带主键的实体。 */
    FolderEntity save(FolderEntity folder);

    /** 更新文件夹的名称、父目录或状态。 */
    void update(FolderEntity folder);

    /** 按主键查询文件夹。 */
    Optional<FolderEntity> findById(Long id);

    /** 查询 Workspace 唯一根目录。 */
    Optional<FolderEntity> findRootByWorkspaceId(Long workspaceId);

    /** 一次查询从指定目录到根目录的祖先链，用于统一判断目录可访问性和移动防环。 */
    List<FolderEntity> listAncestorChain(Long folderId);

    /** 批量读取 Workspace 完整目录树，供可信检索一次性校验祖先链。 */
    List<FolderEntity> listByWorkspaceId(Long workspaceId);

    /** 查询同一父目录下指定名称的有效文件夹。 */
    Optional<FolderEntity> findActiveByParentIdAndName(Long workspaceId, Long parentId, String name);

    /** 查询指定父目录下的直接子文件夹。 */
    List<FolderEntity> listActiveByParentId(Long workspaceId, Long parentId);
}
