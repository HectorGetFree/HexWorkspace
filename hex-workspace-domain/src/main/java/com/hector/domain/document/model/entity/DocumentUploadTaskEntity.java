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
    /** 正式对象应保存的 MIME 类型。 */
    private String contentType;
    /** 任务当前执行状态。 */
    private DocumentUploadTaskStatusVO status;
    /** 当前消费者持有的执行令牌，用于拒绝过期消费者的状态回写。 */
    private String executionToken;
    /** 当前执行令牌的有效截止时间。 */
    private LocalDateTime runningLeaseUntil;
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

}
