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
    private Long ownerUserId;
    private Long sourceFileId;
    private String title;
    private String documentType;
    private String status;
    private Integer currentVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
