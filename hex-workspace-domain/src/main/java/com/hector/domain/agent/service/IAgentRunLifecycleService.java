package com.hector.domain.agent.service;

import com.hector.domain.agent.model.valobj.LifecycleEventVO;

/** 接收并幂等投影 Runtime 生命周期事件。 */
public interface IAgentRunLifecycleService {
    void project(LifecycleEventVO event);
}
