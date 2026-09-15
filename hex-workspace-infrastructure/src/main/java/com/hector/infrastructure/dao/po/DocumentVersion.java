package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 文档版本表映射对象。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVersion {
    private Long id;
    private Long documentId;
    private Long versionNo;
    private Integer storageType;
    private String bucketName;
    private String storagePath;
    private String contentType;
    private Long contentSize;
    private String contentHash;
    private Long createdBy;
    private LocalDateTime createdAt;
}
