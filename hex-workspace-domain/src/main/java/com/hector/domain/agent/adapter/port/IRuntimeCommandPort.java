package com.hector.domain.agent.adapter.port;

import com.hector.domain.agent.model.valobj.RuntimeCommandRequestVO;
import com.hector.domain.agent.model.valobj.RuntimeCommandResultVO;

/** Workspace 到 Hexgent Runtime 的受控命令端口。 */
public interface IRuntimeCommandPort {

    /** 发送命令；网络未知结果必须抛出异常，不得伪装成 REJECTED。 */
    RuntimeCommandResultVO send(RuntimeCommandRequestVO request);
}
