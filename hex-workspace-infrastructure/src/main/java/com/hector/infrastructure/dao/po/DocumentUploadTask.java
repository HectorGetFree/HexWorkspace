package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 文档上传任务表映射对象。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadTask {
    private Long id;
    private String taskCode;
    private Long documentId;
    private String stagingPath;
    private String targetPath;
    private Integer status;
    private String executionToken;
    private LocalDateTime runningLeaseUntil;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
