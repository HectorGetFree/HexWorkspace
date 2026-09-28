package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** AgentRun 表映射对象。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRun {
    private Long id;
    private String runId;
    private String conversationId;
    private Long workspaceId;
    private Long initiatorUserId;
    private Integer status;
    private String instruction;
    private String resourceScope;
    private String allowedTools;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String failureCode;
    private String failureMessage;
}
