package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档内容快照表映射对象。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentContent {

    private Long id;

    private String contentCode;

    private Long workspaceId;

    private Long creatorUserId;

    private String purpose;

    private String originName;

    private String suffix;

    private String contentType;

    private Long contentSize;

    private String storageType;

    private String bucketName;

    private String storagePath;

    private String contentHash;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
