package com.hector.domain.agent.model.valobj;

/** Runtime Transcript 向 Workspace 暴露的最小消息读模型。 */
public record RuntimeConversationMessageVO(Long sequence, String role, String content, String createdAt) {
}
