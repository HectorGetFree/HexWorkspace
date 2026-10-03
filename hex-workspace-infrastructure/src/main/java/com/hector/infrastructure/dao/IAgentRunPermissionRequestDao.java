package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.AgentRunPermissionRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** AgentRun Permission 请求收据 DAO。 */
@Mapper
public interface IAgentRunPermissionRequestDao {
    AgentRunPermissionRequest queryByRequestId(@Param("requestId") String requestId);

    int insertIgnore(AgentRunPermissionRequest request);

    int resolvePending(@Param("requestId") String requestId, @Param("runId") String runId,
                       @Param("status") Integer status, @Param("responseId") String responseId,
                       @Param("resolvedAt") LocalDateTime resolvedAt);
}
