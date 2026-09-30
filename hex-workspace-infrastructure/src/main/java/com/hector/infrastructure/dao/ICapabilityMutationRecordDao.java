package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.CapabilityMutationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ICapabilityMutationRecordDao {
    CapabilityMutationRecord queryByIdentity(@Param("runId") String runId, @Param("callId") String callId);

    int insertIgnore(CapabilityMutationRecord record);

    int claimExpired(@Param("runId") String runId, @Param("callId") String callId,
                     @Param("fingerprint") String fingerprint, @Param("executionToken") String executionToken,
                     @Param("leaseUntil") java.time.LocalDateTime leaseUntil,
                     @Param("now") java.time.LocalDateTime now);

    int markSucceeded(@Param("runId") String runId, @Param("callId") String callId,
                      @Param("fingerprint") String fingerprint, @Param("executionToken") String executionToken,
                      @Param("documentId") Long documentId,
                      @Param("previousVersionId") Long previousVersionId, @Param("versionId") Long versionId);

    int markPending(@Param("runId") String runId, @Param("callId") String callId,
                    @Param("fingerprint") String fingerprint, @Param("executionToken") String executionToken,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage);

    int markFailed(@Param("runId") String runId, @Param("callId") String callId,
                   @Param("fingerprint") String fingerprint, @Param("executionToken") String executionToken,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);
}
