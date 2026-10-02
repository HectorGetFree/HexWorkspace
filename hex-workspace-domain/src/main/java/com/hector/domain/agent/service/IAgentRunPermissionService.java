package com.hector.domain.agent.service;

import com.hector.domain.agent.model.valobj.PermissionDecisionVO;
import com.hector.domain.agent.model.valobj.PermissionRequestVO;

/** 保存 Hexgent Permission 请求收据及用户决定，不管理 Runtime PendingPermission。 */
public interface IAgentRunPermissionService {

    /**
     * 接收 Runtime 的 authoritative PermissionRequest。
     *
     * @return true 表示本次首次持久化，调用方可在事务提交后向浏览器实时发布
     */
    boolean receive(PermissionRequestVO request);

    /** 记录人类决定；相同决定返回原 responseId，以支持 Runtime 投递重试。 */
    PermissionDecisionVO decide(String workspaceRunId, String requestId,
                                String decision, String responseId);
}
