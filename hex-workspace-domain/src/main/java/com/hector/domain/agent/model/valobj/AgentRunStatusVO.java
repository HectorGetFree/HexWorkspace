package com.hector.domain.agent.model.valobj;

/** AgentRun 的业务生命周期状态。 */
public enum AgentRunStatusVO {
    PENDING,
    RUNNING,
    PAUSED,
    INCOMPLETE,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /** 判断是否仍属于 Conversation 的非终态运行。 */
    public boolean isNonTerminal() {
        return this == PENDING || this == RUNNING || this == PAUSED || this == INCOMPLETE;
    }
}
