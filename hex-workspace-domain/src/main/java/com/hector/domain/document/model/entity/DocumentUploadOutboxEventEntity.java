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
    /** 当前发布器实例持有的发布令牌。 */
    private String publishingToken;
    /** 当前发布令牌的有效截止时间。 */
    private LocalDateTime publishingLeaseUntil;
    /** 最近一次投递失败原因。 */
    private String failureReason;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 最后更新时间。 */
    private LocalDateTime updatedAt;

}
