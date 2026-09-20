package com.hector.domain.knowledge.model.valobj;

/** 内部检索的可信调用上下文；用户身份必须由认证边界注入。 */
public record RetrievalContextVO(Long userId, Long workspaceId) {

    public RetrievalContextVO {
        if (userId == null || userId <= 0 || workspaceId == null || workspaceId <= 0) {
            throw new IllegalArgumentException("检索上下文中的用户或 Workspace 标识无效");
        }
    }
}
