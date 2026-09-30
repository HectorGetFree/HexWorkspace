package com.hector.domain.agent.service;

import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;

/** 编排 Workspace Capability 的持久化写操作幂等和业务变更。 */
public interface ICapabilityMutationService {
    CapabilityResultVO updateDocument(CapabilityContextVO context, String argumentsJson);

    CapabilityResultVO createDocument(CapabilityContextVO context, String argumentsJson);
}
