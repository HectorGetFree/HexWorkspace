package com.hector.domain.agent.service.impl;

import com.hector.domain.agent.adapter.port.IRuntimeReconciliationPort;
import com.hector.domain.agent.model.entity.AgentRunEntity;
import com.hector.domain.agent.model.valobj.LifecycleEventVO;
import com.hector.domain.agent.service.IAgentRunLifecycleService;
import com.hector.domain.agent.service.IAgentRunReconciliationService;
import com.hector.domain.agent.service.IAgentRunService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/** 以 Runtime durable lifecycle 为事实源，按序重放 WS 幂等投影。 */
@Service
public class AgentRunReconciliationServiceImpl implements IAgentRunReconciliationService {
    @Resource
    private IAgentRunService runService;
    @Resource
    private IAgentRunLifecycleService lifecycleService;
    @Resource
    private IRuntimeReconciliationPort runtimePort;

    @Override
    public void reconcile(String runId, Long userId) {
        reconcile(runId, null, userId);
    }

    @Override
    public void reconcile(String runId, String commandId, Long userId) {
        AgentRunEntity run = runService.require(runId);
        if (!run.getInitiatorUserId().equals(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN);
        }
        if (!run.getStatus().isNonTerminal()) {
            return;
        }
        var state = runtimePort.readState(runId);
        if (!run.getConversationId().equals(state.conversationId())) {
            throw new BusinessException(ResponseCode.FORBIDDEN);
        }
        if (commandId != null && !commandId.isBlank()) {
            runtimePort.reconcileCommandEffect(commandId);
        }
        for (LifecycleEventVO event : runtimePort.readLifecycleEvents(
                runId, run.getLifecycleSequence())) {
            lifecycleService.project(event);
        }
    }
}
