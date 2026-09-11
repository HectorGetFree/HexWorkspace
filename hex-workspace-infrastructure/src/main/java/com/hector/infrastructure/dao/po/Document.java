package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档表映射对象。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    private Long id;
    private String documentCode;
    private Long workspaceId;
    private Long parentId;
    private Long ownerUserId;
    private String title;
    private String fileName;
    private Integer documentType;
    private Integer status;
    private Integer storageType;
    private String bucketName;
    private String storagePath;
    private String contentType;
    private Long contentSize;
    private String contentHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
