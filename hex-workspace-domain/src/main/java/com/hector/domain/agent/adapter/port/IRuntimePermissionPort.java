package com.hector.domain.agent.adapter.port;

/** Hexgent Permission Resolve 的服务间读取端口。 */
public interface IRuntimePermissionPort {
    void resolve(String workspaceRunId, String requestId, String responseId, String decision);
}
