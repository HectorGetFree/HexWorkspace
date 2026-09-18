package com.hector.domain.knowledge.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexJobEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 索引任务仓储，普通自动任务按稳定请求键幂等登记。 */
public interface IKnowledgeIndexJobRepository {
    /**
     * 仅在同一请求键尚未登记时创建任务。
     *
     * @param job 待登记的索引任务
     * @return 本次是否实际创建了任务
     */
    boolean saveIfAbsent(KnowledgeIndexJobEntity job);

    /**
     * 按稳定请求键查询任务，用于处理重复登记。
     *
     * @param requestKey 触发索引的幂等请求键
     * @return 任务存在时返回任务信息
     */
    Optional<KnowledgeIndexJobEntity> findByRequestKey(String requestKey);

    /** 按任务编码查询当前任务，用于 Worker 重新校验租约归属。 */
    Optional<KnowledgeIndexJobEntity> findByJobCode(String jobCode);

    /**
     * 查询到达重试时间、等待再次投递的任务。
     *
     * @param now 当前时间
     * @param limit 最大返回数量
     * @return 可重新投递的待执行任务
     */
    List<KnowledgeIndexJobEntity> listPendingReady(LocalDateTime now, int limit);

    /**
     * 以状态与租约条件原子抢占一个待执行任务。
     *
     * @param jobCode 任务编码
     * @param workerToken 本次 Worker 的唯一执行令牌
     * @param runningLeaseUntil 执行租约截止时间
     * @return 是否成功获得执行权
     */
    boolean claimForRunning(String jobCode, String workerToken, LocalDateTime runningLeaseUntil);

    /**
     * 仅当前 Worker 可续期，防止长耗时外部调用期间被其他实例接管。
     *
     * @return 是否仍持有有效执行权并成功续租
     */
    boolean renewLeaseIfOwned(String jobCode, String workerToken, LocalDateTime runningLeaseUntil);

    /** 回收执行租约已过期的任务，不增加真实执行失败次数。 */
    boolean recoverExpiredRunning(String jobCode, LocalDateTime now, String failureReason);

    /** 仅由持有执行令牌的 Worker 将过期索引输入任务标记为跳过。 */
    boolean markSkippedIfOwned(String jobCode, String workerToken, String reason);

    /** 查询租约已过期的执行中任务。 */
    List<KnowledgeIndexJobEntity> listExpiredRunning(LocalDateTime now, int limit);

    /**
     * 仅当前有效 Worker 可将任务标记为成功。
     *
     * @return 是否成功更新任务终态
     */
    boolean markSucceededIfOwned(String jobCode, String workerToken, LocalDateTime now);

    /**
     * 当前执行失败后按重试额度回到待执行或进入终态失败。
     *
     * @param maxRetryCount 最大真实失败次数
     * @param nextRetryAt 未耗尽额度时下次可执行时间
     * @param failureReason 本次失败原因
     * @return 是否仍由当前 Worker 成功处理失败状态
     */
    boolean finishFailureIfOwned(String jobCode, String workerToken, int maxRetryCount, LocalDateTime nextRetryAt, String failureReason);
}
