package com.hector.domain.agent.model.entity;

import com.hector.domain.agent.model.valobj.AgentRunStatusVO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次完整用户任务的 Workspace 业务实体。 */
@Data
@Builder
public class AgentRunEntity {
    /** 数据库主键。 */
    private Long id;
    /** 跨暂停、继续和恢复保持不变的业务 Run 标识。 */
    private String runId;
    /** 所属 Workspace Conversation。 */
    private String conversationId;
    /** 所属 Workspace。 */
    private Long workspaceId;
    /** 发起用户。 */
    private Long initiatorUserId;
    /** 当前业务生命周期状态。 */
    private AgentRunStatusVO status;
    /** StartRun 的原始用户指令。 */
    private String instruction;
    /** 创建时确定的最大资源边界 JSON。 */
    private String resourceScope;
    /** 创建时确定的最大工具边界 JSON。 */
    private String allowedTools;
    /** 乐观并发版本。 */
    private Long version;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 开始时间。 */
    private LocalDateTime startedAt;
    /** 终止时间。 */
    private LocalDateTime finishedAt;
    /** 稳定业务失败码。 */
    private String failureCode;
    /** 面向业务的失败信息。 */
    private String failureMessage;
    /** 最近一次已接受的 Workspace 生命周期序号。 */
    private Long lifecycleSequence;
}
