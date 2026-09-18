package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.KnowledgeIndexJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IKnowledgeIndexJobDao {
    int insertIgnore(KnowledgeIndexJob job);

    KnowledgeIndexJob queryByRequestKey(@Param("requestKey") String requestKey);

    KnowledgeIndexJob queryByJobCode(@Param("jobCode") String jobCode);

    int claimForRunning(@Param("jobCode") String jobCode,
                        @Param("workerToken") String workerToken,
                        @Param("runningLeaseUntil") LocalDateTime runningLeaseUntil);

    List<KnowledgeIndexJob> queryExpiredRunning(@Param("now") LocalDateTime now,
                                                @Param("limit") int limit);

    int recoverExpiredRunning(@Param("jobCode") String jobCode,
                              @Param("now") LocalDateTime now,
                              @Param("failureReason") String failureReason);

    int markSkippedIfOwned(@Param("jobCode") String jobCode, @Param("workerToken") String workerToken,
                           @Param("failureReason") String failureReason);
}
