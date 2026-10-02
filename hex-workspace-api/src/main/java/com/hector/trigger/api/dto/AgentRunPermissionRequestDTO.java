package com.hector.trigger.api.dto;

import lombok.Data;

/** Hexgent 权限请求的安全摘要。 */
@Data
public class AgentRunPermissionRequestDTO {
    private String integrationRunId;
    private String requestId;
    private String toolName;
    private String description;
    private String createdAt;
}
