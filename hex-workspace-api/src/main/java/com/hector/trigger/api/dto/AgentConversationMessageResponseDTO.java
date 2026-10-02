package com.hector.trigger.api.dto;

import lombok.Builder;
import lombok.Data;

/** 浏览器可见的 Conversation 历史消息。 */
@Data
@Builder
public class AgentConversationMessageResponseDTO {
    private String id;
    private String role;
    private String content;
    private String createdAt;
}
