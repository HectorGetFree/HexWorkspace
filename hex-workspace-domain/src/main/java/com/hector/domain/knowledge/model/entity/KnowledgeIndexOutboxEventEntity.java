package com.hector.domain.knowledge.model.entity;

import com.hector.domain.knowledge.model.valobj.KnowledgeIndexOutboxStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 知识索引任务的可靠消息投递记录。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeIndexOutboxEventEntity {
    /** 主键。 */
    private Long id;

    /** 事件全局编码。 */
    private String eventCode;

    /** 对应知识索引任务编码。 */
    private String jobCode;

    /** 消息发布状态。 */
    private KnowledgeIndexOutboxStatusVO status;

    /** 当前发布者令牌。 */
    private String publishingToken;

    /** 发布租约截止时间。 */
    private LocalDateTime publishingLeaseUntil;

    /** 最近一次发布失败原因。 */
    private String failureReason;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最后更新时间。 */
    private LocalDateTime updatedAt;
}
