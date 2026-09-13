package com.hector.domain.document.repository;

import com.hector.domain.document.model.entity.DocumentUploadTaskEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 文档上传任务仓储。 */
public interface IDocumentUploadTaskRepository {

    /** 保存新建上传任务并回填主键。 */
    DocumentUploadTaskEntity save(DocumentUploadTaskEntity task);

    /** 按任务编码查询。 */
    Optional<DocumentUploadTaskEntity> findByTaskCode(String taskCode);

    /** 仅当任务仍为 PENDING 或 RETRY_WAIT 时原子抢占执行权。 */
    boolean claimForRunning(String taskCode, String executionToken, LocalDateTime runningLeaseUntil);

    /** 仅当执行令牌仍有效时完成任务。 */
    boolean completeIfOwned(String taskCode, String executionToken);

    /** 仅当执行令牌仍有效时登记本次失败。 */
    boolean markRetryIfOwned(String taskCode, String executionToken, String failureReason, LocalDateTime nextRetryAt);

    /** 查询到达重试时间的失败任务。 */
    List<DocumentUploadTaskEntity> listRetryDue(LocalDateTime now, int limit);

    /**
     * 原子抢占到期重试任务，并重置为等待 MQ 消费状态。
     *
     * @return 是否成功取得本次重新登记 Outbox 的唯一执行权
     */
    boolean claimRetryForDispatch(String taskCode, LocalDateTime now);

    /** 查询已超过执行租约的 RUNNING 任务。 */
    List<DocumentUploadTaskEntity> listExpiredRunning(LocalDateTime now, int limit);

    /** 原子回收超时执行令牌并转入重试等待。 */
    boolean recoverExpiredRunning(String taskCode, LocalDateTime now, LocalDateTime nextRetryAt, String failureReason);
}
