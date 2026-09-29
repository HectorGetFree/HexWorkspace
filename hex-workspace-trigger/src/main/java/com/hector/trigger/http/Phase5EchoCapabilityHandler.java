package com.hector.trigger.http;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hector.domain.agent.adapter.port.ICapabilityHandler;
import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Phase 5 联调专用 Capability；默认关闭，不属于生产业务能力。 */
@Component
@ConditionalOnProperty(name = "hexworkspace.capability.phase5-fixture-enabled", havingValue = "true")
public class Phase5EchoCapabilityHandler implements ICapabilityHandler {
    @Override
    public String capabilityName() {
        return "phase5_echo";
    }

    @Override
    public CapabilityResultVO invoke(CapabilityContextVO context, String argumentsJson) {
        JSONObject arguments = JSON.parseObject(argumentsJson == null ? "{}" : argumentsJson);
        JSONObject data = new JSONObject();
        data.put("message", arguments.getString("message"));
        data.put("initiatorUserId", context.initiatorUserId());
        data.put("workspaceId", context.workspaceId());
        return new CapabilityResultVO(
                "v1", context.runId(), "", CapabilityResultVO.Status.SUCCEEDED,
                data.toJSONString(), null, null);
    }
}
