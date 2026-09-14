package com.hector.domain.document.model.entity;

import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentStorageTypeVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档聚合根。
 * 保存文档的 Workspace 归属、当前内容元数据和生命周期状态。
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

    /** 不含文件后缀的文档展示标题。 */
    private String title;

    /** 包含文件后缀的完整文件名，用于同级目录重名判断。 */
    private String fileName;

    /** 文档来源类型，区分平台创建和本地上传。 */
    private DocumentTypeVO documentType;

    /** 文档当前生命周期状态；只有 ACTIVE 状态可对外读取。 */
    private DocumentStatusVO status;

    /** 内容在 MinIO 中的存储类型。 */
    private DocumentStorageTypeVO storageType;

    /** 内容所在的 MinIO Bucket 名称。 */
    private String bucketName;

    /** 内容在 MinIO 中的对象路径。 */
    private String storagePath;

    /** 内容 MIME 类型。 */
    private String contentType;

    /** 内容字节大小。 */
    private Long contentSize;

    /** 内容 SHA-256 摘要，用于校验和幂等判断。 */
    private String contentHash;

    /** 本地上传请求的客户端幂等键；平台新建文档为空。 */
    private String idempotencyKey;

    /** 文档创建时间。 */
    private LocalDateTime createdAt;

    /** 文档最后更新时间。 */
    private LocalDateTime updatedAt;

    /** 将文档标记为逻辑删除状态。 */
    public void delete() {
        this.status = DocumentStatusVO.DELETED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 使用完整文件名重命名文档，并同步维护展示标题与更新时间。
     *
     * @param newFileName 新的完整文件名
     */
    public void rename(String newFileName) {
        this.fileName = newFileName;
        this.title = newFileName.toLowerCase().endsWith(".md")
                ? newFileName.substring(0, newFileName.length() - 3) : newFileName;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 将文档移动到新的父目录。
     *
     * @param newParentId 目标父目录 ID
     */
    public void moveTo(Long newParentId) {
        this.parentId = newParentId;
        this.updatedAt = LocalDateTime.now();
    }

    /** 将已写入 MinIO 的文档切换为可用状态。 */
    public void activate() {
        this.status = DocumentStatusVO.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    /** 将首次内容写入失败且无法继续重试的文档标记为失败。 */
    public void failUpload() {
        this.status = DocumentStatusVO.FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    /** 判断文档是否处于可用状态。 */
    public boolean isActive() {
        return DocumentStatusVO.ACTIVE == this.status;
    }

    /** 判断文档是否应展示在资料库列表中。 */
    public boolean isVisibleInWorkspace() {
        return DocumentStatusVO.ACTIVE == this.status || DocumentStatusVO.FAILED == this.status;
    }
}
