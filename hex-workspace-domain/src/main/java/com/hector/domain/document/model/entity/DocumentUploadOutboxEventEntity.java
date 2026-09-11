package com.hector.domain.document.model.entity;

import com.hector.domain.document.model.valobj.DocumentUploadOutboxStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 文档上传任务的事务发件箱事件。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadOutboxEventEntity {

    /** 事件主键 ID。 */
    private Long id;
    /** 事件唯一编码。 */
    private String eventCode;
    /** 关联上传任务编码。 */
    private String taskCode;
    /** 事件投递状态。 */
    private DocumentUploadOutboxStatusVO status;
    /** 最近一次投递失败原因。 */
    private String failureReason;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 最后更新时间。 */
    private LocalDateTime updatedAt;

    /** MQ 已确认接收后标记事件已投递。 */
    public void markSent() {
        this.status = DocumentUploadOutboxStatusVO.SENT;
        this.failureReason = null;
        this.updatedAt = LocalDateTime.now();
    }

    /** MQ 投递失败时保留待投递状态。 */
    public void markPending(String reason) {
        this.status = DocumentUploadOutboxStatusVO.PENDING;
        this.failureReason = reason;
        this.updatedAt = LocalDateTime.now();
    }
}
