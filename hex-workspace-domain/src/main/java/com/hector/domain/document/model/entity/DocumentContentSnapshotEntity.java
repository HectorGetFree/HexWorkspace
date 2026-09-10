package com.hector.domain.document.model.entity;

import com.hector.domain.document.model.valobj.DocumentContentSnapshotPurposeVO;
import com.hector.domain.document.model.valobj.DocumentContentSnapshotStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档内容快照实体。
 * 只表达文档版本依赖的不可变内容及其存储元数据，不作为用户可独立管理的业务资源。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentContentSnapshotEntity {

    /** 内容快照主键 ID。 */
    private Long id;

    /** 内容快照唯一编码。 */
    private String contentCode;

    /** 内容快照所属 Workspace ID，用于平台级数据隔离。 */
    private Long workspaceId;

    /** 创建者用户 ID。 */
    private Long creatorUserId;

    /** 内容用途，区分导入内容与系统派生内容。 */
    private DocumentContentSnapshotPurposeVO purpose;

    /** 导入时的原始名称或系统生成的内容名称。 */
    private String originName;

    /** 内容名称后缀。 */
    private String suffix;

    /** MIME 内容类型。 */
    private String contentType;

    /** 内容大小，单位字节。 */
    private Long contentSize;

    /** 存储类型，例如 MINIO。 */
    private String storageType;

    /** 对象存储桶名称。 */
    private String bucketName;

    /** 对象存储路径。 */
    private String storagePath;

    /** 内容摘要值。 */
    private String contentHash;

    /** 内容快照状态。 */
    private DocumentContentSnapshotStatusVO status;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 标记内容快照可以正常使用。 */
    public void markSuccess() {
        changeStatus(DocumentContentSnapshotStatusVO.SUCCESS);
    }

    /** 标记内容快照保存失败。 */
    public void markFailed() {
        changeStatus(DocumentContentSnapshotStatusVO.FAILED);
    }

    /** 标记内容快照已被逻辑删除。 */
    public void markDeleted() {
        changeStatus(DocumentContentSnapshotStatusVO.DELETED);
    }

    /** 统一维护状态变更时间，避免快照状态与更新时间不一致。 */
    private void changeStatus(DocumentContentSnapshotStatusVO targetStatus) {
        this.status = targetStatus;
        this.updatedAt = LocalDateTime.now();
    }
}
