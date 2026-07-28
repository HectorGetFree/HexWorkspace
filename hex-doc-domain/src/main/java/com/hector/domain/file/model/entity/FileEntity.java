package com.hector.domain.file.model.entity;

import com.hector.domain.file.model.valobj.FileStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件实体。
 * 表示业务中流转的文件对象，而不是数据库表映射对象。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileEntity {

    /** 文件主键ID。 */
    private Long id;

    /** 文件唯一编码。 */
    private String fileCode;

    /** 创建者用户ID。 */
    private Long creatorUserId;

    /** 文件展示名称。 */
    private String fileName;

    /** 原始文件名。 */
    private String originName;

    /** 文件后缀。 */
    private String fileSuffix;

    /** 文件内容类型。 */
    private String contentType;

    /** 文件大小，单位字节。 */
    private Long fileSize;

    /** 存储类型，例如 LOCAL。 */
    private String storageType;

    /** 文件桶名称。 */
    private String bucketName;

    /** 文件存储路径。 */
    private String storagePath;

    /** 文件摘要值。 */
    private String fileHash;

    /** 文件状态。 */
    private FileStatusVO status;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    public void markUploaded() {
        this.status = FileStatusVO.UPLOADED;
    }

    public void markSuccess() {
        this.status = FileStatusVO.SUCCESS;
    }

    public void markFailed() {
        this.status = FileStatusVO.FAILED;
    }

    public void markDeleted() {
        this.status = FileStatusVO.DELETED;
    }
}
