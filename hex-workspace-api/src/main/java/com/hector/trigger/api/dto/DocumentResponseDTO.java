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
    private Long ownerUserId;
    private String title;
    private String documentType;
    private String status;
    private Integer currentVersion;
    private Long sourceFileId;
    private Long currentVersionFileId;
    private String parseStatus;
    private Long extractedFileId;
    private String parserVersion;
    private LocalDateTime parsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
