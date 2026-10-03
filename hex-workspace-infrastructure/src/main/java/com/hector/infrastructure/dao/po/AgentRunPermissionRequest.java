package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** AgentRun Permission 请求收据表映射。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunPermissionRequest {
    private Long id;
    private String requestId;
    private String runId;
    private String toolName;
    private String description;
    private Integer status;
    private String responseId;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}
