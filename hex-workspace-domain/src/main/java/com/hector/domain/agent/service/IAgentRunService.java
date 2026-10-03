package com.hector.domain.agent.service;

import com.hector.domain.agent.model.entity.AgentRunEntity;
import com.hector.domain.agent.model.valobj.AgentRunCommandTypeVO;
import com.hector.domain.agent.model.valobj.RuntimeCommandResultVO;
import java.util.List;

/** AgentRun 创建与业务边界服务。 */
public interface IAgentRunService {
    AgentRunEntity start(String conversationId, Long userId, String instruction,
                         String resourceScope, String allowedTools);
    AgentRunEntity require(String runId);
    List<AgentRunEntity> listByConversationId(String conversationId);

    AgentRunEntity startAndDispatch(String conversationId, Long userId, String instruction,
                                    String resourceScope, String allowedTools);

    RuntimeCommandResultVO dispatch(String conversationId, String runId, Long userId,
                                    AgentRunCommandTypeVO commandType, String payloadJson);
}
