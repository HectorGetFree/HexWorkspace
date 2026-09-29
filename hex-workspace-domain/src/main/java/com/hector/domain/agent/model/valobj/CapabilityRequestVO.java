package com.hector.domain.agent.model.valobj;

import com.alibaba.fastjson.JSON;

/** Hexgent 调用 Workspace Capability 的协议请求。 */
public record CapabilityRequestVO(
        String protocolVersion,
        String runId,
        String callId,
        String capability,
        Object arguments) {

    /** 将协议层 Object 参数统一转换为 Handler 使用的 JSON 文本。 */
    public String argumentsJson() {
        if (arguments == null) {
            return "{}";
        }
        return arguments instanceof String ? (String) arguments : JSON.toJSONString(arguments);
    }
}
