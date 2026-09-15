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
    private Long currentVersionNo;
    private String idempotencyKey;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
