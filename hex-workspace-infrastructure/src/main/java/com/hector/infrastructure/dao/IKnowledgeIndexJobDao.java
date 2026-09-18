package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.KnowledgeIndexJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 知识索引任务状态机的 MyBatis DAO。 */
@Mapper
public interface IKnowledgeIndexJobDao {
    /** 按请求键幂等插入任务。 */
    int insertIgnore(KnowledgeIndexJob job);

    /** 按稳定请求键查询任务。 */
    KnowledgeIndexJob queryByRequestKey(@Param("requestKey") String requestKey);

    /** 按业务编码查询任务。 */
    KnowledgeIndexJob queryByJobCode(@Param("jobCode") String jobCode);

    /** 查询已到重试时间的待执行任务。 */
    List<KnowledgeIndexJob> queryPendingReady(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 以 PENDING 状态条件更新抢占 Worker 执行权。 */
    int claimForRunning(@Param("jobCode") String jobCode,
                        @Param("workerToken") String workerToken,
                        @Param("runningLeaseUntil") LocalDateTime runningLeaseUntil);
    /** 仅当前 Worker Token 可延长未过期租约。 */
    int renewLeaseIfOwned(@Param("jobCode") String jobCode, @Param("workerToken") String workerToken,
                          @Param("runningLeaseUntil") LocalDateTime runningLeaseUntil);

    /** 查询需要回收的超时执行任务。 */
    List<KnowledgeIndexJob> queryExpiredRunning(@Param("now") LocalDateTime now,
                                                @Param("limit") int limit);

    /** 回收已过期的执行租约。 */
    int recoverExpiredRunning(@Param("jobCode") String jobCode,
                              @Param("now") LocalDateTime now,
                              @Param("failureReason") String failureReason);

    /** 仅当前 Worker 可将过期索引输入标记为跳过。 */
    int markSkippedIfOwned(@Param("jobCode") String jobCode, @Param("workerToken") String workerToken,
                           @Param("failureReason") String failureReason);

    /** 仅当前持有有效租约的 Worker 可将任务标记为成功。 */
    int markSucceededIfOwned(@Param("jobCode") String jobCode, @Param("workerToken") String workerToken,
                             @Param("now") LocalDateTime now);

    /** 按剩余重试次数，将失败任务转为待重试或终态失败。 */
    int finishFailureIfOwned(@Param("jobCode") String jobCode, @Param("workerToken") String workerToken,
                             @Param("maxRetryCount") int maxRetryCount, @Param("nextRetryAt") LocalDateTime nextRetryAt,
                             @Param("failureReason") String failureReason);
}
