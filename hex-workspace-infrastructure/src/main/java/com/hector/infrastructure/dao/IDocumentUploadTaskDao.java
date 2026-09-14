package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.DocumentUploadTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 文档上传任务 DAO。 */
@Mapper
public interface IDocumentUploadTaskDao {
    int insert(DocumentUploadTask task);
    DocumentUploadTask queryByTaskCode(@Param("taskCode") String taskCode);
    int countByStagingPath(@Param("stagingPath") String stagingPath);
    List<DocumentUploadTask> queryExpiredTerminalTasksWithStagingPath(@Param("expiredBefore") LocalDateTime expiredBefore,
                                                                       @Param("limit") int limit);
    int clearStagingPathIfTerminal(@Param("taskCode") String taskCode, @Param("stagingPath") String stagingPath);
    int claimForRunning(@Param("taskCode") String taskCode, @Param("executionToken") String executionToken,
                        @Param("runningLeaseUntil") LocalDateTime runningLeaseUntil);
    int completeIfOwned(@Param("taskCode") String taskCode, @Param("executionToken") String executionToken);
    int markRetryIfOwned(@Param("taskCode") String taskCode, @Param("executionToken") String executionToken,
                         @Param("failureReason") String failureReason, @Param("nextRetryAt") LocalDateTime nextRetryAt);
    int markFailedIfOwned(@Param("taskCode") String taskCode, @Param("executionToken") String executionToken,
                          @Param("failureReason") String failureReason);
    List<DocumentUploadTask> queryRetryDue(@Param("now") LocalDateTime now, @Param("limit") int limit);
    int claimRetryForDispatch(@Param("taskCode") String taskCode, @Param("now") LocalDateTime now);
    List<DocumentUploadTask> queryExpiredRunning(@Param("now") LocalDateTime now, @Param("limit") int limit);
    int recoverExpiredRunning(@Param("taskCode") String taskCode, @Param("now") LocalDateTime now,
                              @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("failureReason") String failureReason);
    int markExpiredRunningFailed(@Param("taskCode") String taskCode, @Param("now") LocalDateTime now,
                                 @Param("failureReason") String failureReason);
}
