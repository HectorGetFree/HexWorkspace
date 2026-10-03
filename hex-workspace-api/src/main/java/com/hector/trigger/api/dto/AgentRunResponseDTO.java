package com.hector.trigger.api.dto;

import lombok.Builder;
import lombok.Data;

/** AgentRun 响应；Command ACCEPTED 阶段仍返回 PENDING。 */
@Data
@Builder
public class AgentRunResponseDTO {
    private String runId;
    private String conversationId;
    private Long workspaceId;
    private String status;
    private String instruction;
    private String failureCode;
    private String failureMessage;
    private String createdAt;
    private String finishedAt;
}
