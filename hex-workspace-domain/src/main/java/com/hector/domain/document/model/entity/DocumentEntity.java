package com.hector.domain.document.model.entity;

import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档聚合根。
 * 保存文档的Workspace归属、当前状态和当前版本指针，不承载文件存储或 AI 处理细节。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEntity {

    /** 文档主键 ID。 */
    private Long id;

    /** 文档唯一业务编码，用于接口路径和跨模块引用。 */
    private String documentCode;

    /** 文档所属的个人 Workspace ID。 */
    private Long workspaceId;

    /** 文档所在的父文件夹 ID。 */
    private Long parentId;

    /** 文档所有者用户 ID；当前阶段等同于 Workspace Owner。 */
    private Long ownerUserId;

    /** 文档首次创建时对应的底层内容对象 ID。 */
    private Long sourceFileId;

    /** 不含文件后缀的文档展示标题。 */
    private String title;

    /** 包含文件后缀的完整文件名，用于同级目录重名判断。 */
    private String fileName;

    /** 文档来源类型，区分平台创建和本地上传。 */
    private DocumentTypeVO documentType;

    /** 文档当前生命周期状态。 */
    private DocumentStatusVO status;

    /** 当前生效的文档版本号。 */
    private Integer currentVersion;

    /** 文档创建时间。 */
    private LocalDateTime createdAt;

    /** 文档最后更新时间。 */
    private LocalDateTime updatedAt;

    public void archive() {
        this.status = DocumentStatusVO.ARCHIVED;
    }

    public void activate() {
        this.status = DocumentStatusVO.ACTIVE;
    }

    public void delete() {
        this.status = DocumentStatusVO.DELETED;
    }

    public void rename(String newFileName) {
        this.fileName = newFileName;
        this.title = newFileName.toLowerCase().endsWith(".md")
                ? newFileName.substring(0, newFileName.length() - 3) : newFileName;
        this.updatedAt = LocalDateTime.now();
    }

    public void moveTo(Long newParentId) {
        this.parentId = newParentId;
        this.updatedAt = LocalDateTime.now();
    }

    public void switchCurrentVersion(Integer versionNumber) {
        this.currentVersion = versionNumber;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return DocumentStatusVO.ACTIVE == this.status;
    }
}
