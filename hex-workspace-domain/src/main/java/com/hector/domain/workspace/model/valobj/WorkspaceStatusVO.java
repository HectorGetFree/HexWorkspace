package com.hector.domain.workspace.model.valobj;

/** Workspace 生命周期状态。 */
public enum WorkspaceStatusVO {
    ACTIVE(0),
    ARCHIVED(1),
    DELETED(2);

    /** 持久化状态码，禁止依赖枚举声明顺序。 */
    private final int code;

    WorkspaceStatusVO(int code) { this.code = code; }

    public int getCode() { return code; }

    public static WorkspaceStatusVO fromCode(Integer code) {
        for (WorkspaceStatusVO value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("未知 Workspace 状态码: " + code);
    }
}
