package com.hector.domain.agent.model.valobj;

import java.time.OffsetDateTime;

/** Hexgent 投递到 Workspace 的安全权限请求摘要。 */
public record PermissionRequestVO(String workspaceRunId, String requestId, String toolName,
                                  String description, OffsetDateTime createdAt) { }
