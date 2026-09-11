package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档详情响应对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponseDTO implements Serializable {

    private Long documentId;
    private String documentCode;
    private Long workspaceId;
    private Long parentId;
    private Long ownerUserId;
    private String title;
    private String fileName;
    private String source;
    private String content;
    private String documentType;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
