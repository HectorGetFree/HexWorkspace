package com.hector.domain.knowledge.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexJobEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 索引任务仓储，普通自动任务按稳定请求键幂等登记。 */
public interface IKnowledgeIndexJobRepository {
    /** 仅在同一请求键尚未登记时创建任务。 */
    boolean saveIfAbsent(KnowledgeIndexJobEntity job);

    Optional<KnowledgeIndexJobEntity> findByRequestKey(String requestKey);

    /** 按任务编码查询当前任务，用于 Worker 重新校验租约归属。 */
    Optional<KnowledgeIndexJobEntity> findByJobCode(String jobCode);

    /** 以状态与租约条件原子抢占一个待执行任务。 */
    boolean claimForRunning(String jobCode, String workerToken, LocalDateTime runningLeaseUntil);

    /** 回收执行租约已过期的任务，不增加真实执行失败次数。 */
    boolean recoverExpiredRunning(String jobCode, LocalDateTime now, String failureReason);

    /** 仅由持有执行令牌的 Worker 将过期索引输入任务标记为跳过。 */
    boolean markSkippedIfOwned(String jobCode, String workerToken, String reason);

    /** 查询租约已过期的执行中任务。 */
    List<KnowledgeIndexJobEntity> listExpiredRunning(LocalDateTime now, int limit);
}
