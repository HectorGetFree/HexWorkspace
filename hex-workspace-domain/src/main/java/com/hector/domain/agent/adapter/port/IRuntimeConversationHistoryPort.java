package com.hector.domain.agent.adapter.port;

import com.hector.domain.agent.model.valobj.RuntimeConversationMessageVO;

import java.util.List;

/** 从 Hexgent durable Transcript 读取已脱敏的 Conversation 消息投影。 */
public interface IRuntimeConversationHistoryPort {
    List<RuntimeConversationMessageVO> readMessages(String workspaceRunId);
}
