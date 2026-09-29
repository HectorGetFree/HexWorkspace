package com.hector.domain.agent.adapter.port;

import java.io.InputStream;

/** Hexgent 非权威 Stream 的服务间读取端口。 */
public interface IRuntimeStreamPort {
    InputStream open(String workspaceRunId);
}
