package com.hector.domain.folder.model.entity;

import com.hector.domain.folder.model.valobj.FolderStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Workspace 目录树中的文件夹实体。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FolderEntity {

    /** 文件夹主键 ID。 */
    private Long id;

    /** 文件夹唯一业务编码。 */
    private String folderCode;

    /** 文件夹所属的个人 Workspace ID。 */
    private Long workspaceId;

    /** 父文件夹 ID；根目录该字段为空。 */
    private Long parentId;

    /** 文件夹展示名称。 */
    private String name;

    /** 是否为 Workspace 唯一根目录。 */
    private Boolean root;

    /** 文件夹当前生命周期状态。 */
    private FolderStatusVO status;

    /** 显式逻辑删除时间；未删除时为空，供后续回收站保留期计算使用。 */
    private LocalDateTime deletedAt;

    /** 文件夹创建时间。 */
    private LocalDateTime createdAt;

    /** 文件夹最后更新时间。 */
    private LocalDateTime updatedAt;

    /** 判断当前节点是否为 Workspace 根目录。 */
    public boolean isRoot() {
        return Boolean.TRUE.equals(root);
    }

    /** 判断文件夹是否仍处于可用状态。 */
    public boolean isActive() {
        return FolderStatusVO.ACTIVE == status;
    }

    /** 判断文件夹是否处于显式逻辑删除状态。 */
    public boolean isDeleted() {
        return FolderStatusVO.DELETED == status;
    }

    /** 修改文件夹名称并同步业务更新时间。 */
    public void rename(String newName) {
        this.name = newName;
        this.updatedAt = LocalDateTime.now();
    }

    /** 将文件夹移动到新的父目录并同步业务更新时间。 */
    public void moveTo(Long newParentId) {
        this.parentId = newParentId;
        this.updatedAt = LocalDateTime.now();
    }

    /** 将文件夹标记为逻辑删除，不在领域层物理清理后代内容。 */
    public void delete() {
        if (isDeleted()) {
            return;
        }
        this.status = FolderStatusVO.DELETED;
        this.deletedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** 恢复被显式删除的目录；后代状态和文档内容均不在此处改写。 */
    public void restore() {
        if (!isDeleted()) {
            return;
        }
        this.status = FolderStatusVO.ACTIVE;
        this.deletedAt = null;
        this.updatedAt = LocalDateTime.now();
    }
}
