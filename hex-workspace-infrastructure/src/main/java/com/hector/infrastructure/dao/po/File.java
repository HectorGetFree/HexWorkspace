package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件表映射对象。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class File {

    private Long id;

    private String fileCode;

    private Long workspaceId;

    private Long creatorUserId;

    private String purpose;

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
