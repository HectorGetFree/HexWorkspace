package com.hector.trigger.api.dto;

import lombok.Data;

/** 创建 Workspace Conversation 的请求。 */
@Data
public class WorkspaceConversationCreateRequestDTO {
    /** 会话展示标题。 */
    private String title;
}
