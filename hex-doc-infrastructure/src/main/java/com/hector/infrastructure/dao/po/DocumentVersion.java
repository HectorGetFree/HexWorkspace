package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档版本表映射对象。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVersion {

    private Long id;
    private Long documentId;
    private Integer versionNumber;
    private Long fileId;
    private String contentHash;
    private String extractedText;
    private String parseStatus;
    private String changeSummary;
    private Long createdByUserId;
    private LocalDateTime createdAt;
}
