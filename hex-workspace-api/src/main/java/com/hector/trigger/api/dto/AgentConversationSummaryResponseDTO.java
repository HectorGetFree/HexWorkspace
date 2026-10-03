package com.hector.trigger.api.dto;

import lombok.Builder;
import lombok.Data;

/** Sidebar Conversation 历史条目。 */
@Data
@Builder
public class AgentConversationSummaryResponseDTO {
    private String conversationId;
    private Long workspaceId;
    private String title;
    private String updatedAt;
    private String latestRunStatus;
}
