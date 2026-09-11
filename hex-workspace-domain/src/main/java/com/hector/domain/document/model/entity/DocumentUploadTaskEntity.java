package com.hector.domain.document.model.entity;

import com.hector.domain.document.model.valobj.DocumentUploadTaskStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 文档从临时对象转正为正式 MinIO 对象的异步任务。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadTaskEntity {

    /** 任务主键 ID。 */
    private Long id;
    /** 任务唯一编码，也是 MQ 消息的幂等键。 */
    private String taskCode;
    /** 对应文档主键 ID。 */
    private Long documentId;
    /** MinIO 临时对象路径。 */
    private String stagingPath;
    /** MinIO 正式对象路径。 */
    private String targetPath;
    /** 任务当前执行状态。 */
    private DocumentUploadTaskStatusVO status;
    /** 已执行失败的次数。 */
    private Integer retryCount;
    /** 下次允许重新投递的时间。 */
    private LocalDateTime nextRetryAt;
    /** 最近一次失败原因。 */
    private String failureReason;
    /** 任务创建时间。 */
    private LocalDateTime createdAt;
    /** 任务最后更新时间。 */
    private LocalDateTime updatedAt;

    /** 将待执行任务标记为执行中；重复消息不会重复抢占。 */
    public void start() {
        this.status = DocumentUploadTaskStatusVO.RUNNING;
        this.updatedAt = LocalDateTime.now();
    }

    /** 将完成正式对象写入的任务标记为成功。 */
    public void succeed() {
        this.status = DocumentUploadTaskStatusVO.SUCCEEDED;
        this.failureReason = null;
        this.updatedAt = LocalDateTime.now();
    }

    /** 记录失败并等待后续定时任务重新投递。 */
    public void waitForRetry(String reason, LocalDateTime retryAt) {
        this.status = DocumentUploadTaskStatusVO.RETRY_WAIT;
        this.retryCount = retryCount + 1;
        this.failureReason = reason;
        this.nextRetryAt = retryAt;
        this.updatedAt = LocalDateTime.now();
    }

    /** 将到期的失败任务重新置为待消费状态。 */
    public void readyForRetry() {
        this.status = DocumentUploadTaskStatusVO.PENDING;
        this.nextRetryAt = null;
        this.updatedAt = LocalDateTime.now();
    }
}
