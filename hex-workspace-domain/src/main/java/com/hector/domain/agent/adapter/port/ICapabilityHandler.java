package com.hector.domain.agent.adapter.port;

import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;

/** Capability 处理器；不得从模型参数获取用户或 Workspace 权限。 */
public interface ICapabilityHandler {
    String capabilityName();

    CapabilityResultVO invoke(CapabilityContextVO context, String argumentsJson);
}
