package com.hector.infrastructure.service;

import com.alibaba.fastjson.JSON;
import com.hector.domain.agent.model.valobj.AgentRunStatusVO;
import com.hector.domain.agent.model.valobj.LifecycleEventVO;
import com.hector.domain.agent.service.IAgentRunLifecycleService;
import com.hector.infrastructure.dao.IAgentRunDao;
import com.hector.domain.agent.adapter.port.IBrowserEventPublisher;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/** 通过事件收据与状态 CAS 实现 AgentRun 生命周期幂等投影。 */
@Service
public class AgentRunLifecycleServiceImpl implements IAgentRunLifecycleService {
    @Resource
    private IAgentRunDao dao;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IBrowserEventPublisher browserEventPublisher;

    @Override
    public void project(LifecycleEventVO event) {
        boolean[] projected = {false};
        transactionTemplate.executeWithoutResult(status -> {
            if (dao.insertLifecycleEvent(
                    event.eventId(),
                    event.workspaceRunId(),
                    event.sequence(),
                    event.type()) == 0) {
                return;
            }
            int targetStatus = targetStatus(event.type());
            String failureCode = null;
            String failureMessage = null;
            if (targetStatus == AgentRunStatusVO.FAILED.ordinal()) {
                var payload = JSON.parseObject(event.payloadJson() == null ? "{}" : event.payloadJson());
                failureCode = payload.getString("failureCode");
                failureMessage = payload.getString("failureMessage");
            }

            projected[0] = dao.updateLifecycle(
                    event.workspaceRunId(),
                    event.sequence(),
                    targetStatus,
                    failureCode,
                    failureMessage,
                    targetStatus >= AgentRunStatusVO.SUCCEEDED.ordinal()
                            ? LocalDateTime.now()
                            : null) == 1;
            // CAS 未命中表示事件已过期、终态已落库或前置状态不匹配；
            // 仅保留收据，不向浏览器投递会造成状态回退的过期事件。
        });
        if (projected[0]) {
            browserEventPublisher.publishLifecycle(event);
            if (isTerminal(event.type())) {
                browserEventPublisher.publishEnd(event.workspaceRunId());
            }
        }
    }

    private int targetStatus(String type) {
        return switch (type) {
            case "AgentRunStarted", "AgentRunResumed", "AgentRunContinued" -> 1;
            case "AgentRunPaused" -> 2;
            case "AgentRunIncomplete" -> 3;
            case "AgentRunSucceeded" -> 4;
            case "AgentRunFailed" -> 5;
            case "AgentRunCancelled" -> 6;
            default -> throw new IllegalArgumentException("未知生命周期事件：" + type);
        };
    }

    private boolean isTerminal(String type) {
        return "AgentRunSucceeded".equals(type)
                || "AgentRunFailed".equals(type)
                || "AgentRunCancelled".equals(type);
    }
}
