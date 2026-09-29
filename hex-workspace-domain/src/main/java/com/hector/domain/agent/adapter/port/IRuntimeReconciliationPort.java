package com.hector.domain.agent.adapter.port;

import com.hector.domain.agent.model.valobj.LifecycleEventVO;
import com.hector.domain.agent.model.valobj.RuntimeLifecycleStateVO;

import java.util.List;

/** 从 Hexgent durable Runtime 读取对账所需事实。 */
public interface IRuntimeReconciliationPort {
    RuntimeLifecycleStateVO readState(String runId);
    List<LifecycleEventVO> readLifecycleEvents(String runId, long afterSequence);
    void reconcileCommandEffect(String commandId);
}
