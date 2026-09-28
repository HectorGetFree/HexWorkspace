package com.hector.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** Workspace 中绑定 Hexgent Session 的会话业务实体。 */
@Data
@Builder
public class WorkspaceConversationEntity {
    /** 数据库主键。 */
    private Long id;
    /** 对外稳定会话标识，也是 Hexgent Session 的绑定键。 */
    private String conversationId;
    /** 所属 Workspace。 */
    private Long workspaceId;
    /** 创建会话的用户。 */
    private Long userId;
    /** 会话标题。 */
    private String title;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}
