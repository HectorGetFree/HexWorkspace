package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** knowledge_index_outbox_event 表映射对象。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeIndexOutboxEvent {
    private Long id;
    private String eventCode;
    private String jobCode;
    private Integer status;
    private String publishingToken;
    private LocalDateTime publishingLeaseUntil;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
