package com.hector.domain.agent.model.entity;

import com.hector.domain.agent.model.valobj.CapabilityMutationStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Workspace Capability 写操作的持久化幂等记录。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityMutationRecordEntity {
    private Long id;
    private String runId;
    private String callId;
    private String capability;
    private String requestFingerprint;
    private CapabilityMutationStatusVO status;
    private String executionToken;
    private LocalDateTime leaseUntil;
    private Long documentId;
    private Long previousVersionId;
    private Long versionId;
    private Long targetVersionNo;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
