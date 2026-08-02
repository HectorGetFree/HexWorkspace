package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件响应对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileResponseDTO implements Serializable {

    private Long fileId;

    private String fileCode;

    private Long tenantId;

    private Long creatorUserId;

    private String fileName;

    private String originName;

    private String fileSuffix;

    private String contentType;

    private Long fileSize;

    private String storageType;

    private String bucketName;

    private String storagePath;

    private String fileHash;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
