package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** capability_mutation_record 表映射对象。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityMutationRecord {
    private Long id;
    private String runId;
    private String callId;
    private String capability;
    private String requestFingerprint;
    private Integer status;
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
