package com.hector.domain.agent.repository;

import com.hector.domain.agent.model.entity.AgentRunEntity;

import java.util.Optional;

/** AgentRun 持久化接口。 */
public interface IAgentRunRepository {
    AgentRunEntity save(AgentRunEntity entity);
    Optional<AgentRunEntity> findByRunId(String runId);
    Optional<AgentRunEntity> findNonTerminalByConversationId(String conversationId);
    boolean markRejected(String runId, String failureCode, String failureMessage);
}
