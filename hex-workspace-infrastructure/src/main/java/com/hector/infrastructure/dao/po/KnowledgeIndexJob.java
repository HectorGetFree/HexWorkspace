package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** knowledge_index_job 表映射对象。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeIndexJob {
    private Long id;
    private String jobCode;
    private String requestKey;
    private Long workspaceId;
    private Long documentId;
    private Long versionNo;
    private Long indexInputRevision;
    private Integer jobType;
    private Integer status;
    private Integer stage;
    private String workerToken;
    private LocalDateTime runningLeaseUntil;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
