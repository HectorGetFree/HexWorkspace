package com.hector.domain.agent.adapter.port;

import com.hector.domain.agent.model.valobj.CapabilityRequestVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;

/** Workspace Capability Gateway 的领域端口。 */
public interface ICapabilityGateway {
    CapabilityResultVO invoke(CapabilityRequestVO request);
}
