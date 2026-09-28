package com.hector.trigger.api.dto;

import lombok.Data;

/** Continue、Pause、Resume、Steer、Cancel 的请求。 */
@Data
public class AgentRunCommandRequestDTO {
    /** SteerRun 使用的新指令，其余命令可为空。 */
    private String instruction;
}
