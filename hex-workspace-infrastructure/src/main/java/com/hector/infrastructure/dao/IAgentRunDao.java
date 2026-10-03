package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.AgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

/** AgentRun DAO。 */
@Mapper
public interface IAgentRunDao {
    int insert(AgentRun agentRun);
    AgentRun queryByRunId(@Param("runId") String runId);
    AgentRun queryNonTerminalByConversationId(@Param("conversationId") String conversationId);
    List<AgentRun> queryByConversationId(@Param("conversationId") String conversationId);
    int markRejected(@Param("runId") String runId, @Param("failureCode") String failureCode,
                     @Param("failureMessage") String failureMessage);
    int insertLifecycleEvent(@Param("eventId") String eventId, @Param("runId") String runId,
                             @Param("sequence") Long sequence, @Param("eventType") String eventType);
    int updateLifecycle(@Param("runId") String runId, @Param("sequence") Long sequence,
                        @Param("status") int status, @Param("failureCode") String failureCode,
                        @Param("failureMessage") String failureMessage, @Param("finishedAt") LocalDateTime finishedAt);
}
