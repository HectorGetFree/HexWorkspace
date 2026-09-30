package com.hector.infrastructure.repository;

import com.hector.domain.agent.model.entity.CapabilityMutationRecordEntity;
import com.hector.domain.agent.model.valobj.CapabilityMutationStatusVO;
import com.hector.domain.agent.repository.ICapabilityMutationRecordRepository;
import com.hector.infrastructure.dao.ICapabilityMutationRecordDao;
import com.hector.infrastructure.dao.po.CapabilityMutationRecord;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Capability 写操作幂等记录仓储实现。 */
@Repository
public class CapabilityMutationRecordRepository implements ICapabilityMutationRecordRepository {
    @Resource
    private ICapabilityMutationRecordDao dao;

    @Override
    public Optional<CapabilityMutationRecordEntity> findByIdentity(String runId, String callId) {
        return Optional.ofNullable(dao.queryByIdentity(runId, callId)).map(this::toEntity);
    }

    @Override
    public boolean saveIfAbsent(CapabilityMutationRecordEntity record) {
        return dao.insertIgnore(toPo(record)) == 1;
    }

    @Override
    public boolean claimExpired(String runId, String callId, String fingerprint, String executionToken,
                                java.time.LocalDateTime leaseUntil, java.time.LocalDateTime now) {
        return dao.claimExpired(runId, callId, fingerprint, executionToken, leaseUntil, now) == 1;
    }

    @Override
    public boolean markSucceeded(String runId, String callId, String fingerprint, String executionToken, Long documentId,
                                 Long previousVersionId, Long versionId) {
        return dao.markSucceeded(runId, callId, fingerprint, executionToken, documentId, previousVersionId, versionId) == 1;
    }

    @Override
    public boolean markPending(String runId, String callId, String fingerprint, String executionToken,
                               String errorCode, String errorMessage) {
        return dao.markPending(runId, callId, fingerprint, executionToken, errorCode, errorMessage) == 1;
    }

    @Override
    public boolean markFailed(String runId, String callId, String fingerprint, String executionToken,
                              String errorCode, String errorMessage) {
        return dao.markFailed(runId, callId, fingerprint, executionToken, errorCode, errorMessage) == 1;
    }

    private CapabilityMutationRecord toPo(CapabilityMutationRecordEntity entity) {
        return CapabilityMutationRecord.builder()
                .id(entity.getId()).runId(entity.getRunId()).callId(entity.getCallId())
                .capability(entity.getCapability()).requestFingerprint(entity.getRequestFingerprint())
                .status(entity.getStatus().getCode()).documentId(entity.getDocumentId())
                .executionToken(entity.getExecutionToken()).leaseUntil(entity.getLeaseUntil())
                .previousVersionId(entity.getPreviousVersionId()).versionId(entity.getVersionId())
                .targetVersionNo(entity.getTargetVersionNo()).errorCode(entity.getErrorCode())
                .errorMessage(entity.getErrorMessage()).createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt()).build();
    }

    private CapabilityMutationRecordEntity toEntity(CapabilityMutationRecord po) {
        return CapabilityMutationRecordEntity.builder()
                .id(po.getId()).runId(po.getRunId()).callId(po.getCallId())
                .capability(po.getCapability()).requestFingerprint(po.getRequestFingerprint())
                .status(CapabilityMutationStatusVO.fromCode(po.getStatus())).documentId(po.getDocumentId())
                .executionToken(po.getExecutionToken()).leaseUntil(po.getLeaseUntil())
                .previousVersionId(po.getPreviousVersionId()).versionId(po.getVersionId())
                .targetVersionNo(po.getTargetVersionNo()).errorCode(po.getErrorCode())
                .errorMessage(po.getErrorMessage()).createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt()).build();
    }
}
