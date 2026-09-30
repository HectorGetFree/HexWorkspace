package com.hector.domain.agent.repository;

import com.hector.domain.agent.model.entity.CapabilityMutationRecordEntity;

import java.util.Optional;

/** Capability 写操作幂等记录仓储。 */
public interface ICapabilityMutationRecordRepository {
    Optional<CapabilityMutationRecordEntity> findByIdentity(String runId, String callId);

    boolean saveIfAbsent(CapabilityMutationRecordEntity record);

    boolean claimExpired(String runId, String callId, String fingerprint, String executionToken,
                         java.time.LocalDateTime leaseUntil, java.time.LocalDateTime now);

    boolean markSucceeded(String runId, String callId, String fingerprint, String executionToken, Long documentId,
                          Long previousVersionId, Long versionId);

    boolean markPending(String runId, String callId, String fingerprint, String executionToken,
                        String errorCode, String errorMessage);

    boolean markFailed(String runId, String callId, String fingerprint, String executionToken,
                       String errorCode, String errorMessage);
}
