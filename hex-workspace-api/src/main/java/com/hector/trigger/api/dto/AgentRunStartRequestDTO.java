package com.hector.trigger.api.dto;

import lombok.Data;

/** StartRun 的业务请求；资源和工具边界在创建时固定。 */
@Data
public class AgentRunStartRequestDTO {
    /** 用户交给 Agent 的首条指令。 */
    private String instruction;
    /** 最大资源边界 JSON。 */
    private String resourceScope = "{}";
    /** 最大工具边界 JSON。 */
    private String allowedTools = "[]";
}
