package com.hector.trigger.api.dto;

import lombok.Data;

/** 用户对 AgentRun Permission 请求的决定。 */
@Data
public class AgentRunPermissionResolveRequestDTO {
    private String responseId;
    private String decision;
}
