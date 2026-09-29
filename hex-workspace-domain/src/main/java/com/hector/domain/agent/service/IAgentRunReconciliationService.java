package com.hector.domain.agent.service;

/** 对账 Hexgent durable lifecycle，复用既有幂等生命周期投影。 */
public interface IAgentRunReconciliationService {
    void reconcile(String runId, Long userId);
    void reconcile(String runId, String commandId, Long userId);
}
