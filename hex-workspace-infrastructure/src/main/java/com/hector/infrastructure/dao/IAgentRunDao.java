package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.AgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** AgentRun DAO。 */
@Mapper
public interface IAgentRunDao {
    int insert(AgentRun agentRun);
    AgentRun queryByRunId(@Param("runId") String runId);
    AgentRun queryNonTerminalByConversationId(@Param("conversationId") String conversationId);
    int markRejected(@Param("runId") String runId, @Param("failureCode") String failureCode,
                     @Param("failureMessage") String failureMessage);
}
